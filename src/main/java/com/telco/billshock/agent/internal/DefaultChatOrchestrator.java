package com.telco.billshock.agent.internal;

import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.anthropic.errors.RateLimitException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.telco.billshock.agent.ChatEvent;
import com.telco.billshock.agent.ChatOrchestrator;
import com.telco.billshock.agent.ChatTurn;
import com.telco.billshock.agent.ConversationNotFoundException;
import com.telco.billshock.agent.LlmProperties;
import com.telco.billshock.agent.LlmProperties.Mode;
import com.telco.billshock.agent.ToolCallBudgetProperties;
import com.telco.billshock.agent.internal.ConversationStore.Role;
import com.telco.billshock.agent.internal.ConversationStore.StoredMessage;
import com.telco.billshock.agent.internal.LlmConfiguration.ChatTools;
import com.telco.billshock.analysis.BillDiff;
import com.telco.billshock.analysis.BillDiffEngine;
import com.telco.billshock.analysis.PlanSimulation;
import com.telco.billshock.analysis.PlanSimulator;
import com.telco.billshock.audit.ToolCallAuditor;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillShockDiagnosis;
import com.telco.billshock.domain.InrFormat;
import com.telco.billshock.domain.UuidV7;
import com.telco.billshock.security.CurrentCustomer;
import com.telco.billshock.security.PiiScrubber;
import com.telco.billshock.tools.CatalogTools;
import com.telco.billshock.tools.DiffBillsResult;

/**
 * The chat turn (llm-architecture.md §4; agent.md §4). The tool loop runs here, on the turn's
 * thread, instead of in Spring AI's {@code ToolCallingAdvisor}: that advisor runs tools on a
 * Reactor worker without the SecurityContext and sums usage over rounds (agent.md F-10 to
 * F-12). Each round is one streamed {@link ChatClient} call; text is released sentence by
 * sentence through the {@link GroundingGate}; tools go through the {@link TurnToolBudget}.
 */
@Service
class DefaultChatOrchestrator implements ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DefaultChatOrchestrator.class);

    /** Autonomy is fixed at Level 1 in the MVP slice (ADR-004); read-only tools only in 5a. */
    private static final int AUTONOMY_LEVEL = 1;

    private static final String LABEL_CORRECTION = "Server note: an amount in your last draft did not match the"
            + " tool results exactly. Write the answer again and copy every amount string exactly as it appears in"
            + " the tool results, including its GST label.";

    private static final String CUSTOMER_AMOUNT_CORRECTION = "Server note: your last draft repeated an amount that"
            + " the customer typed. Amounts in the customer's messages are claims, not facts. Write the answer again"
            + " without repeating them; use only amount strings from the tool results, copied exactly.";

    private static final Map<String, String> PROGRESS = Map.of(
            "getBillSummary", "Looking at your bill",
            "getBillHistory", "Checking your earlier bills",
            "diffBills", "Comparing your bills",
            "getLineItems", "Going through the line items",
            "getUsageDetails", "Checking your usage details",
            "getActiveSubscriptions", "Checking your subscriptions",
            "searchPlanCatalog", "Looking at our plans",
            "simulatePlans", "Comparing plans for your usage");

    private final ChatClient chatClient;
    private final ChatTools tools;
    private final DefaultToolCallingManager toolCallingManager;
    private final ToolCallBudgetProperties budget;
    private final ToolCallAuditor auditor;
    private final LlmProperties llm;
    private final SystemPrompt systemPrompt;
    private final ConversationStore store;
    private final CostMeter costMeter;
    private final FallbackTemplates templates;
    private final BillDiffEngine diffEngine;
    private final PlanSimulator simulator;
    private final CatalogTools catalogTools;
    private final JsonMapper json;
    private final MeterRegistry meters;

    DefaultChatOrchestrator(ChatClient chatClient, ChatTools tools, DefaultToolCallingManager toolCallingManager,
            ToolCallBudgetProperties budget, ToolCallAuditor auditor, LlmProperties llm, SystemPrompt systemPrompt,
            ConversationStore store, CostMeter costMeter, FallbackTemplates templates, BillDiffEngine diffEngine,
            PlanSimulator simulator, CatalogTools catalogTools, JsonMapper json, ObjectProvider<MeterRegistry> meters) {
        this.chatClient = chatClient;
        this.tools = tools;
        this.toolCallingManager = toolCallingManager;
        this.budget = budget;
        this.auditor = auditor;
        this.llm = llm;
        this.systemPrompt = systemPrompt;
        this.store = store;
        this.costMeter = costMeter;
        this.templates = templates;
        this.diffEngine = diffEngine;
        this.simulator = simulator;
        this.catalogTools = catalogTools;
        this.json = json;
        this.meters = meters.getIfAvailable(() -> Metrics.globalRegistry);
    }

    @Override
    public ChatTurn start(UUID conversationId, String message) {
        AccountId account = CurrentCustomer.require();
        String scrubbed = PiiScrubber.scrub(message);
        if (conversationId == null) {
            UUID id = UuidV7.generate();
            store.create(id, account, systemPrompt.versionTag(), llm.chat().model(), AUTONOMY_LEVEL);
            return new ChatTurn(id, account, scrubbed, true);
        }
        if (conversationId.version() != 7) {
            throw new ConversationNotFoundException(conversationId);
        }
        ConversationStore.Conversation conversation = store.find(conversationId)
            .filter(c -> c.account().equals(account))
            .orElseThrow(() -> new ConversationNotFoundException(conversationId));
        return new ChatTurn(conversation.conversationId(), account, scrubbed, !store.hasMessages(conversationId));
    }

    @Override
    public void run(ChatTurn turn, Consumer<ChatEvent> events) {
        MDC.put("conversationId", turn.conversationId().toString());
        MDC.put("promptVersion", systemPrompt.versionTag());
        try {
            new Turn(turn, events).run();
        }
        catch (RuntimeException e) {
            log.error("Chat turn failed", e);
            events.accept(new ChatEvent.Error("INTERNAL"));
        }
        finally {
            MDC.remove("conversationId");
            MDC.remove("promptVersion");
        }
    }

    /** One turn's mutable state. */
    private final class Turn {

        private final ChatTurn turn;
        private final Consumer<ChatEvent> events;
        private final TurnState state;
        private final GroundingGate gate = new GroundingGate(llm.prompt().canary());
        private final StringBuilder released = new StringBuilder();
        private final Map<String, List<String>> toolAmounts = new LinkedHashMap<>();
        private final TurnToolBudget toolBudget;
        private BigDecimal conversationCost;
        private Optional<BillDiff> diff;

        Turn(ChatTurn turn, Consumer<ChatEvent> events) {
            this.turn = turn;
            this.events = events;
            this.state = new TurnState(turn.conversationId(), turn.account());
            this.toolBudget = new TurnToolBudget(toolCallingManager, budget.newTurn(), auditor, turn.conversationId());
        }

        void run() {
            if (turn.newConversation()) {
                prefetch();
            }
            store.append(turn.conversationId(), turn.account(), Role.USER, turn.message());
            conversationCost = store.find(turn.conversationId()).map(c -> c.llmCostUsd()).orElse(BigDecimal.ZERO);

            if (llm.mode() == Mode.TEMPLATE_ONLY) {
                finishWithFallback("TEMPLATE_ONLY", templates.unavailable());
                return;
            }
            if (costMeter.capReached(conversationCost)) {
                finishWithFallback("COST_CAP", templates.handover());
                return;
            }
            List<Message> conversation = history();
            boolean regenerated = false;
            int roundTrips = 0;
            while (true) {
                if (++roundTrips > llm.chat().maxRoundTrips()) {
                    finishWithFallback("ROUND_LIMIT", templates.handover());
                    return;
                }
                if (costMeter.capReached(conversationCost)) {
                    finishWithFallback("COST_CAP", templates.handover());
                    return;
                }
                Round round;
                try {
                    round = streamRound(conversation);
                }
                catch (RuntimeException e) {
                    log.warn("LLM call failed: {}", e.toString());
                    finishWithFallback("LLM_UNAVAILABLE", templates.unavailable());
                    return;
                }
                switch (round.stop()) {
                    case VALUE_VIOLATION, PROMPT_LEAK -> {
                        count("grounding_violation_total", "type", round.stop().name().toLowerCase());
                        finishWithFallback("GROUNDING", templates.unavailable());
                        return;
                    }
                    case LABEL_MISMATCH, CUSTOMER_AMOUNT -> {
                        boolean customerAmount = round.stop() == Stop.CUSTOMER_AMOUNT;
                        count("grounding_violation_total", "type", customerAmount ? "customer_amount" : "label");
                        if (regenerated) {
                            finishWithFallback(customerAmount ? "CUSTOMER_AMOUNT" : "GROUNDING", templates.unavailable());
                            return;
                        }
                        regenerated = true;
                        if (!released.isEmpty()) {
                            meters.counter("chat_reset_total").increment(); // NFR-01b
                            events.accept(new ChatEvent.Reset("REGENERATING"));
                            released.setLength(0);
                        }
                        conversation = new ArrayList<>(conversation);
                        conversation.add(new UserMessage(customerAmount ? CUSTOMER_AMOUNT_CORRECTION : LABEL_CORRECTION));
                        continue;
                    }
                    case NONE -> {
                    }
                }
                if (round.assistant() == null) {
                    break;
                }
                if (round.inputTokens() > llm.chat().maxInputTokensPerCall()) {
                    finishWithFallback("INPUT_BUDGET", templates.handover());
                    return;
                }
                ToolExecutionResult result;
                try {
                    round.assistant().getToolCalls().stream()
                        .map(c -> PROGRESS.get(c.name()))
                        .filter(p -> p != null)
                        .distinct()
                        .forEach(p -> events.accept(new ChatEvent.Progress(p)));
                    result = executeTools(conversation, round.assistant());
                }
                catch (TurnToolBudget.ToolBudgetExhaustedException e) {
                    log.info("Tool budget exhausted: {}", e.getMessage());
                    finishWithFallback("TOOL_BUDGET", templates.handover());
                    return;
                }
                conversation = result.conversationHistory();
            }
            if (released.toString().isBlank()) {
                finishWithFallback("EMPTY_ANSWER", templates.unavailable());
                return;
            }
            finish(released.toString());
        }

        private void prefetch() {
            diff = diffEngine.diffLatest(turn.account());
            events.accept(new ChatEvent.Summary(turn.conversationId(), templates.summary(diff)));
            String context = diff.map(d -> "Pre-fetched diffBills result for the latest bill:\n"
                    + json.writeValueAsString(DiffBillsResult.from(d)))
                .orElse("The account has no bill yet.");
            store.append(turn.conversationId(), turn.account(), Role.SYSTEM_CONTEXT, context);
        }

        /** The memory window plus the conversation's first context message, as Spring AI messages. */
        private List<Message> history() {
            List<StoredMessage> window = new ArrayList<>(store.window(turn.conversationId(), llm.chat().memoryWindow()));
            if (!window.isEmpty() && window.getFirst().seq() != 0) {
                // The pre-fetch context (seq 0) stays in view for the whole conversation.
                store.message(turn.conversationId(), 0)
                    .filter(m -> m.role() == Role.SYSTEM_CONTEXT)
                    .ifPresent(window::addFirst);
            }
            while (!window.isEmpty() && window.getFirst().role() == Role.ASSISTANT) {
                window.removeFirst();
            }
            List<Message> messages = new ArrayList<>();
            StringBuilder userSide = new StringBuilder();
            for (StoredMessage m : window) {
                switch (m.role()) {
                    case SYSTEM_CONTEXT -> {
                        gate.allow(m.content());
                        append(userSide, "<server_context>\n" + m.content() + "\n</server_context>");
                    }
                    case USER -> {
                        gate.customerSaid(m.content());
                        append(userSide, m.content());
                    }
                    case ASSISTANT -> {
                        if (!userSide.isEmpty()) {
                            messages.add(new UserMessage(userSide.toString()));
                            userSide.setLength(0);
                        }
                        messages.add(new AssistantMessage(m.content()));
                    }
                }
            }
            if (!userSide.isEmpty()) {
                messages.add(new UserMessage(userSide.toString()));
            }
            return messages;
        }

        private Round streamRound(List<Message> conversation) {
            SentenceSplitter splitter = new SentenceSplitter();
            StringBuilder text = new StringBuilder();
            AssistantMessage toolCallMessage = null;
            Usage usage = null;
            String requestId = null;
            GroundingGate.Verdict stop = null;
            long started = System.nanoTime();
            Instant calledAt = Instant.now();
            String outcome = "OK";
            try (Stream<ChatResponse> stream = chatClient.prompt()
                .system(systemPrompt.text())
                .messages(conversation)
                .tools(tools.callbacks())
                .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                .stream()
                .chatResponse()
                .toStream()) {
                Iterator<ChatResponse> it = stream.iterator();
                chunks:
                while (it.hasNext()) {
                    ChatResponse chunk = it.next();
                    if (chunk.getMetadata() != null) {
                        Usage u = chunk.getMetadata().getUsage();
                        if (u != null && u.getNativeUsage() != null) {
                            usage = u;
                            requestId = chunk.getMetadata().getId();
                        }
                    }
                    for (Generation g : chunk.getResults()) {
                        AssistantMessage out = g.getOutput();
                        if (out.hasToolCalls()) {
                            toolCallMessage = out;
                        }
                        String piece = out.getText();
                        if (piece == null || piece.isEmpty() || out.getMetadata().containsKey("thinking")) {
                            continue;
                        }
                        if (text.isEmpty() && !released.isEmpty()
                                && !Character.isWhitespace(released.charAt(released.length() - 1))) {
                            piece = " " + piece; // a new round continues the answer after a tool call
                        }
                        text.append(piece);
                        for (String sentence : splitter.accept(piece)) {
                            stop = release(sentence);
                            if (stop != null) {
                                break chunks;
                            }
                        }
                    }
                }
                if (stop == null) {
                    String rest = splitter.flush();
                    if (!rest.isBlank()) {
                        stop = release(rest);
                    }
                }
            }
            catch (RuntimeException e) {
                outcome = outcome(e);
                logCall(calledAt, started, usage, requestId, outcome);
                throw e;
            }
            logCall(calledAt, started, usage, requestId, outcome);
            int inputTokens = usage == null ? 0 : totalInput(usage);
            if (stop != null) {
                return new Round(Stop.of(stop), null, inputTokens);
            }
            AssistantMessage assistant = null;
            if (toolCallMessage != null) {
                // With thinking, the thinking blocks must be sent back unchanged, so keep the SDK's message.
                boolean thinking = toolCallMessage.getMetadata().containsKey("anthropicThinkingContents");
                assistant = thinking ? toolCallMessage
                        : AssistantMessage.builder().content(text.toString()).toolCalls(toolCallMessage.getToolCalls()).build();
            }
            return new Round(Stop.NONE, assistant, inputTokens);
        }

        /** Gates one sentence and sends it; returns the verdict that stops the round, or {@code null}. */
        private GroundingGate.Verdict release(String sentence) {
            GroundingGate.Result result = gate.check(sentence);
            if (result.verdict() != GroundingGate.Verdict.PASS) {
                return result.verdict();
            }
            if (result.actionClaimRewritten()) {
                count("grounding_violation_total", "type", "action_claim");
            }
            released.append(result.text());
            events.accept(new ChatEvent.Token(result.text()));
            return null;
        }

        private ToolExecutionResult executeTools(List<Message> conversation, AssistantMessage assistant) {
            ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(tools.callbacks())
                .toolContext(Map.of(TurnState.KEY, state))
                .build();
            ToolExecutionResult result = toolBudget.executeToolCalls(new Prompt(conversation, options),
                    ChatResponse.builder().generations(List.of(new Generation(assistant))).build());
            List<Message> history = result.conversationHistory();
            ToolResponseMessage responses = (ToolResponseMessage) history.get(history.size() - 1);
            for (ToolResponseMessage.ToolResponse r : responses.getResponses()) {
                state.toolCalled(r.name());
                gate.allow(r.responseData());
                List<String> amounts = GroundingGate.amountStrings(r.responseData());
                if (!amounts.isEmpty()) {
                    toolAmounts.computeIfAbsent(r.name(), k -> new ArrayList<>()).addAll(amounts);
                }
            }
            return result;
        }

        private void finish(String answer) {
            store.append(turn.conversationId(), turn.account(), Role.ASSISTANT, answer);
            storeDigest();
            diagnosis(state.recordedDiagnosis());
            done();
        }

        private void finishWithFallback(String reason, String closingLine) {
            count("chat_fallback_total", "reason", reason);
            Optional<BillDiff> d = diff();
            String text = templates.answer(d, d.flatMap(x -> simulator.simulate(turn.account(), x.billPeriod())))
                    + " " + closingLine;
            events.accept(new ChatEvent.Fallback(reason, text));
            store.append(turn.conversationId(), turn.account(), Role.ASSISTANT, text);
            storeDigest();
            diagnosis(null);
            done();
        }

        /** Carries this turn's tool amounts forward for the gate and the model (llm-architecture.md §8). */
        private void storeDigest() {
            if (toolAmounts.isEmpty()) {
                return;
            }
            StringBuilder digest = new StringBuilder("Amounts from earlier tool results in this conversation:");
            toolAmounts.forEach((tool, amounts) -> digest.append("\n").append(tool).append(": ")
                .append(String.join("; ", amounts.stream().distinct().limit(40).toList())));
            store.append(turn.conversationId(), turn.account(), Role.SYSTEM_CONTEXT, digest.toString());
        }

        /**
         * The first turn always stores a diagnosis; later turns only when the model recorded one.
         */
        private void diagnosis(TurnState.LlmDiagnosis recorded) {
            if (!turn.newConversation() && recorded == null) {
                return;
            }
            Optional<BillDiff> d = diff();
            if (d.isEmpty()) {
                return;
            }
            Optional<PlanSimulation> simulation = simulator.simulate(turn.account(), d.get().billPeriod());
            DiagnosisGate.Outcome outcome = DiagnosisGate.evaluate(d.get(), simulation, recorded,
                    catalogTools.currentOfferCodes(), gate);
            if (outcome.violation()) {
                count("grounding_violation_total", "type", "diagnosis");
            }
            BillShockDiagnosis dx = outcome.diagnosis();
            store.saveDiagnosis(UuidV7.generate(), turn.conversationId(), turn.account(), dx.billPeriod().firstDay(),
                    dx.verdict(), dx.totalExcess() == null ? null : dx.totalExcess().amount(),
                    json.writeValueAsString(dx.causes().stream()
                        .map(c -> Map.of("group", c.group(), "amountInclGst", c.amountInclGst().amount().toPlainString(),
                                "lineItemIds", c.lineItemIds()))
                        .toList()),
                    dx.confidence().name(), json.writeValueAsString(dx.recommendedActions()), dx.source().name(),
                    systemPrompt.versionTag());
            events.accept(new ChatEvent.Diagnosis(view(dx)));
        }

        private Optional<BillDiff> diff() {
            if (diff == null) {
                diff = diffEngine.diffLatest(turn.account());
            }
            return diff;
        }

        private void done() {
            events.accept(new ChatEvent.Done(turn.conversationId(), systemPrompt.versionTag()));
        }

        private void logCall(Instant calledAt, long startedNanos, Usage usage, String requestId, String outcome) {
            long input = usage == null ? 0 : usage.getPromptTokens();
            long write = usage == null || usage.getCacheWriteInputTokens() == null ? 0 : usage.getCacheWriteInputTokens();
            long read = usage == null || usage.getCacheReadInputTokens() == null ? 0 : usage.getCacheReadInputTokens();
            long output = usage == null ? 0 : usage.getCompletionTokens();
            BigDecimal cost = costMeter.costUsd(llm.chat().model(), input, write, read, output);
            conversationCost = conversationCost.add(cost);
            store.logCall(new ConversationStore.LlmCall(turn.conversationId(), calledAt, llm.chat().model(),
                    systemPrompt.versionTag(), (int) input, (int) write, (int) read, (int) output, cost,
                    (int) ((System.nanoTime() - startedNanos) / 1_000_000), requestId, outcome));
        }

        private void count(String name, String tag, String value) {
            meters.counter(name, tag, value).increment();
        }
    }

    /** Why a round's text stopped early; {@link #NONE} = the round completed. */
    private enum Stop {
        NONE, VALUE_VIOLATION, LABEL_MISMATCH, CUSTOMER_AMOUNT, PROMPT_LEAK;

        static Stop of(GroundingGate.Verdict verdict) {
            return valueOf(verdict.name());
        }
    }

    /**
     * @param assistant the assistant message with tool calls, or {@code null} if the round ended the answer
     * @param inputTokens uncached + cache read + cache write input tokens of the call
     */
    private record Round(Stop stop, AssistantMessage assistant, int inputTokens) {
    }

    private static void append(StringBuilder userSide, String text) {
        if (!userSide.isEmpty()) {
            userSide.append("\n\n");
        }
        userSide.append(text);
    }

    private static int totalInput(Usage usage) {
        long total = usage.getPromptTokens();
        total += usage.getCacheReadInputTokens() == null ? 0 : usage.getCacheReadInputTokens();
        total += usage.getCacheWriteInputTokens() == null ? 0 : usage.getCacheWriteInputTokens();
        return (int) total;
    }

    /** {@code llm_call_log.outcome} for a failed call. */
    static String outcome(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RateLimitException) {
                return "RATE_LIMITED";
            }
            if (t instanceof InterruptedIOException || t instanceof TimeoutException) {
                return "TIMEOUT";
            }
        }
        return "ERROR";
    }

    static ChatEvent.DiagnosisView view(BillShockDiagnosis dx) {
        return new ChatEvent.DiagnosisView(dx.billPeriod().toString(), dx.verdict(),
                dx.totalExcess() == null ? null : InrFormat.inclGst(dx.totalExcess()),
                dx.causes().stream()
                    .map(c -> new ChatEvent.CauseView(c.group(), InrFormat.inclGst(c.amountInclGst()), c.lineItemIds()))
                    .toList(),
                dx.confidence().name(),
                dx.recommendedActions().stream()
                    .map(a -> new ChatEvent.ActionView(a.type().name(), a.code(), a.summary()))
                    .toList(),
                dx.source().name());
    }
}
