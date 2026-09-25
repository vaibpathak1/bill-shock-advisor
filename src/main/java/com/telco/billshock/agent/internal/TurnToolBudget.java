package com.telco.billshock.agent.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.telco.billshock.agent.ToolCallBudget;
import com.telco.billshock.agent.ToolCallBudget.Admission;
import com.telco.billshock.audit.ToolCallAuditor;
import com.telco.billshock.audit.ToolCallAuditor.Outcome;

/**
 * Decorator around the shared {@code DefaultToolCallingManager} that applies the per-turn
 * {@link ToolCallBudget} to every single tool call (Q-29; llm-architecture.md §9; agent.md §5).
 * The built-in limits cannot exclude a tool from the total (F-5), so they are only the backstop.
 *
 * <ul>
 * <li>{@code ALLOWED}: executed by the delegate.</li>
 * <li>{@code ALREADY_CALLED_THIS_TURN}: not executed; a synthetic result says so; the turn goes on.</li>
 * <li>{@code LIMIT_REACHED}: {@link ToolBudgetExhaustedException}; the orchestrator escalates.</li>
 * </ul>
 * Every tool call, executed or refused, goes to the {@link ToolCallAuditor} (5a answer Q-33).
 * One instance per turn; not thread-safe.
 */
final class TurnToolBudget implements ToolCallingManager {

    static final String ALREADY_DONE = "{\"status\":\"ALREADY_DONE_THIS_TURN\"}";

    private final ToolCallingManager delegate;
    private final ToolCallBudget budget;
    private final ToolCallAuditor auditor;
    private final UUID conversationId;

    TurnToolBudget(ToolCallingManager delegate, ToolCallBudget budget, ToolCallAuditor auditor, UUID conversationId) {
        this.delegate = delegate;
        this.budget = budget;
        this.auditor = auditor;
        this.conversationId = conversationId;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        AssistantMessage original = chatResponse.getResults()
            .stream()
            .map(Generation::getOutput)
            .filter(AssistantMessage::hasToolCalls)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No tool call requested by the chat model"));

        List<ToolCall> admitted = new ArrayList<>();
        Map<String, String> refused = new HashMap<>();
        for (ToolCall call : original.getToolCalls()) {
            Admission admission = budget.admit(call.name());
            switch (admission) {
                case ALLOWED -> admitted.add(call);
                case ALREADY_CALLED_THIS_TURN -> {
                    refused.put(call.id(), ALREADY_DONE);
                    auditor.toolCalled(conversationId, call.name(), call.arguments(), sha256(ALREADY_DONE),
                            Outcome.REFUSED_ALREADY_CALLED);
                }
                case LIMIT_REACHED -> {
                    auditor.toolCalled(conversationId, call.name(), call.arguments(), sha256(""),
                            Outcome.REFUSED_BUDGET);
                    throw new ToolBudgetExhaustedException(call.name());
                }
            }
        }

        Map<String, ToolResponse> executed = new HashMap<>();
        if (!admitted.isEmpty()) {
            AssistantMessage onlyAdmitted = AssistantMessage.builder()
                .content(original.getText())
                .properties(original.getMetadata())
                .toolCalls(admitted)
                .build();
            ToolExecutionResult result;
            try {
                result = delegate.executeToolCalls(prompt,
                        ChatResponse.builder().from(chatResponse).generations(List.of(new Generation(onlyAdmitted))).build());
            }
            catch (ToolCallLimitExceededException e) {
                throw new ToolBudgetExhaustedException(e.getMessage());
            }
            List<Message> history = result.conversationHistory();
            ToolResponseMessage responses = (ToolResponseMessage) history.get(history.size() - 1);
            for (ToolResponse r : responses.getResponses()) {
                executed.put(r.id(), r);
                String args = admitted.stream().filter(c -> c.id().equals(r.id())).findFirst().map(ToolCall::arguments)
                    .orElse("");
                auditor.toolCalled(conversationId, r.name(), args, sha256(r.responseData()), Outcome.EXECUTED);
            }
        }

        // Every tool_use id gets a result, in the model's original order.
        List<ToolResponse> merged = new ArrayList<>();
        for (ToolCall call : original.getToolCalls()) {
            ToolResponse r = executed.get(call.id());
            merged.add(r != null ? r : new ToolResponse(call.id(), call.name(), refused.getOrDefault(call.id(), ALREADY_DONE)));
        }
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        history.add(original);
        history.add(ToolResponseMessage.builder().responses(merged).build());
        return ToolExecutionResult.builder().conversationHistory(history).build();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The counted tool-call limit of the turn is used up (SPEC §4.5): escalate. */
    static final class ToolBudgetExhaustedException extends RuntimeException {

        ToolBudgetExhaustedException(String detail) {
            super("Tool-call budget exhausted: " + detail);
        }
    }
}
