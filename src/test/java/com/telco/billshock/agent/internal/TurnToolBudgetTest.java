package com.telco.billshock.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.telco.billshock.agent.ToolCallBudget;
import com.telco.billshock.audit.ToolCallAuditor;
import com.telco.billshock.audit.ToolCallAuditor.Outcome;
import com.telco.billshock.domain.AccountId;

/** The per-turn budget decorator (Q-29; llm-architecture.md §9; agent.md §5). */
class TurnToolBudgetTest {

    /** Executes every call it receives and records which ones. */
    private static final class RecordingDelegate implements ToolCallingManager {

        final List<String> executed = new ArrayList<>();

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
            return List.of();
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            AssistantMessage message = chatResponse.getResult().getOutput();
            List<ToolResponse> responses = new ArrayList<>();
            for (ToolCall call : message.getToolCalls()) {
                executed.add(call.id());
                responses.add(new ToolResponse(call.id(), call.name(), "result-" + call.id()));
            }
            List<Message> history = new ArrayList<>(prompt.getInstructions());
            history.add(message);
            history.add(ToolResponseMessage.builder().responses(responses).build());
            return ToolExecutionResult.builder().conversationHistory(history).build();
        }
    }

    private record Audit(String tool, Outcome outcome) {
    }

    private final RecordingDelegate delegate = new RecordingDelegate();
    private final List<Audit> audits = new ArrayList<>();
    private final List<String> auditedArguments = new ArrayList<>();
    private final ToolCallAuditor auditor = (account, conversationId, tool, args, sha, outcome) -> {
        audits.add(new Audit(tool, outcome));
        auditedArguments.add(args);
    };
    /** Refuses a goodwill amount of 999.00, standing in for "not found in the tool results" (A-110). */
    private final TurnToolBudget.ArgumentCheck check = call -> call.name().equals("proposeGoodwillCredit")
            && call.arguments().contains("999.00") ? Optional.of("{\"status\":\"REFUSED\"}") : Optional.empty();
    private final TurnToolBudget budget = new TurnToolBudget(delegate,
            new ToolCallBudget(8, Set.of("escalateToHuman", "recordDiagnosis")), auditor, AccountId.of(1001),
            UUID.randomUUID(), check);
    private final Prompt prompt = new Prompt(List.of(new UserMessage("Why?")));

    private static ChatResponse calls(ToolCall... calls) {
        return ChatResponse.builder()
            .generations(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())))
            .build();
    }

    private static ToolCall call(String id, String name) {
        return new ToolCall(id, "function", name, "{}");
    }

    private static List<ToolResponse> responses(ToolExecutionResult result) {
        List<Message> history = result.conversationHistory();
        return ((ToolResponseMessage) history.getLast()).getResponses();
    }

    @Test
    void anUngroundedArgumentIsRefusedAndAuditedWithoutRunningTheTool() {
        ToolExecutionResult result = budget.executeToolCalls(prompt, calls(
                new ToolCall("g1", "function", "proposeGoodwillCredit", "{\"amountExclGst\":\"999.00\"}"),
                new ToolCall("g2", "function", "proposeGoodwillCredit",
                        "{\"amountExclGst\":\"876.00\",\"reason\":\"call me on 9876543210\"}")));

        assertThat(delegate.executed).containsExactly("g2");
        assertThat(responses(result)).extracting(ToolResponse::responseData)
            .containsExactly("{\"status\":\"REFUSED\"}", "result-g2");
        assertThat(audits).containsExactly(new Audit("proposeGoodwillCredit", Outcome.REFUSED_UNGROUNDED_ARGUMENT),
                new Audit("proposeGoodwillCredit", Outcome.EXECUTED));
        // Arguments reach the audit log PII-scrubbed.
        assertThat(auditedArguments.getLast()).contains("[PHONE]").doesNotContain("9876543210");
    }

    @Test
    void eightCountedCallsRunAndTheNinthStopsTheTurn() {
        for (int i = 1; i <= 8; i++) {
            budget.executeToolCalls(prompt, calls(call("c" + i, "getBillSummary")));
        }
        assertThatThrownBy(() -> budget.executeToolCalls(prompt, calls(call("c9", "getBillSummary"))))
            .isInstanceOf(TurnToolBudget.ToolBudgetExhaustedException.class);
        assertThat(delegate.executed).hasSize(8);
        assertThat(audits.getLast()).isEqualTo(new Audit("getBillSummary", Outcome.REFUSED_BUDGET));
    }

    @Test
    void recordDiagnosisIsNotCountedAndStillRunsAfterEightCalls() {
        for (int i = 1; i <= 8; i++) {
            budget.executeToolCalls(prompt, calls(call("c" + i, "diffBills")));
        }
        ToolExecutionResult result = budget.executeToolCalls(prompt, calls(call("d1", "recordDiagnosis")));
        assertThat(responses(result)).extracting(ToolResponse::responseData).containsExactly("result-d1");
    }

    @Test
    void aRepeatedOncePerTurnCallIsNotExecutedAndTheBatchKeepsItsOrder() {
        ToolExecutionResult result = budget.executeToolCalls(prompt,
                calls(call("a", "recordDiagnosis"), call("b", "getLineItems"), call("c", "recordDiagnosis")));

        assertThat(delegate.executed).containsExactly("a", "b");
        assertThat(responses(result)).extracting(ToolResponse::id).containsExactly("a", "b", "c");
        assertThat(responses(result).get(2).responseData()).isEqualTo(TurnToolBudget.ALREADY_DONE);
        // The history keeps the model's original message with all three tool_use ids.
        List<Message> history = result.conversationHistory();
        assertThat(((AssistantMessage) history.get(history.size() - 2)).getToolCalls()).hasSize(3);
        assertThat(audits).containsExactly(new Audit("recordDiagnosis", Outcome.REFUSED_ALREADY_CALLED),
                new Audit("recordDiagnosis", Outcome.EXECUTED), new Audit("getLineItems", Outcome.EXECUTED));
    }

    @Test
    void aBatchCrossingTheLimitExecutesNothing() {
        for (int i = 1; i <= 7; i++) {
            budget.executeToolCalls(prompt, calls(call("c" + i, "getUsageDetails")));
        }
        assertThatThrownBy(() -> budget.executeToolCalls(prompt,
                calls(call("x", "getBillSummary"), call("y", "simulatePlans"))))
            .isInstanceOf(TurnToolBudget.ToolBudgetExhaustedException.class);
        assertThat(delegate.executed).hasSize(7);
    }
}
