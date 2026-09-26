package com.telco.billshock.tools;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

import com.telco.billshock.actions.ActionRequest;
import com.telco.billshock.actions.ActionView;
import com.telco.billshock.actions.ExecutionStep.StepStatus;
import com.telco.billshock.actions.GuardrailProperties;
import com.telco.billshock.actions.ProposalResult;
import com.telco.billshock.actions.ProposedActionService;
import com.telco.billshock.domain.AccountId;

/**
 * What the chat orchestrator needs from the action workflow, through {@code tools} so that
 * {@code agent} keeps its module dependencies (architecture.md §4; actions.md §3.3–§3.5): the tools
 * to register per autonomy level, the SSE {@code action} event, the per-turn digest of the
 * conversation's actions, and the deterministic escalation when the tool budget is spent.
 */
@Component
public class ConversationActions {

    /** The tools that may create an action; their results carry an {@code actionId}. */
    public static final Set<String> ACTION_TOOL_NAMES = Set.of("proposeGoodwillCredit", "proposeVasUnsubscribe",
            "proposeThirdPartyBarring", "proposePlanChange", "proposeAddOn", "raiseDispute", "escalateToHuman");

    private final ProposedActionService actions;
    private final ActionTools actionTools;
    private final EscalationTool escalationTool;
    private final GuardrailProperties guardrails;
    private final JsonMapper json;

    public ConversationActions(ProposedActionService actions, ActionTools actionTools, EscalationTool escalationTool,
            GuardrailProperties guardrails, JsonMapper json) {
        this.actions = actions;
        this.actionTools = actionTools;
        this.escalationTool = escalationTool;
        this.guardrails = guardrails;
        this.json = json;
    }

    /** The configured autonomy level (fixed at 1 in the MVP slice, A-109), stored on each conversation. */
    public int autonomyLevel() {
        return guardrails.autonomyLevel();
    }

    /** Level 0: only {@code escalateToHuman}; Level 1 and up: every action tool (ADR-004, A-109). */
    public List<Object> toolObjects() {
        return guardrails.autonomyLevel() == 0 ? List.of(escalationTool) : List.of(actionTools, escalationTool);
    }

    /** The SSE {@code action} event (actions.md §3.3): server wording only. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActionEvent(long actionId, String type, String status, String summary, String amount,
            boolean needsSupervisorReview, String expiresAt, String reference, String message) {

        static ActionEvent of(ActionView a) {
            return new ActionEvent(a.actionId(), a.type().name(), a.status().name(), a.summary(),
                    a.amount() == null ? null : a.amount().display(), a.needsSupervisorReview(), a.expiresAt(),
                    a.reference(), a.message());
        }
    }

    /** The action a tool result refers to, if it created or found one. */
    public Optional<ActionEvent> eventFor(AccountId account, String toolResult) {
        try {
            JsonNode node = json.readTree(toolResult);
            if (node == null || !node.hasNonNull("actionId")) {
                return Optional.empty();
            }
            return actions.find(account, node.get("actionId").asLong()).map(ActionEvent::of);
        }
        catch (JacksonException e) {
            return Optional.empty();
        }
    }

    /**
     * The conversation's actions as server context for the next turn (actions.md §3.5), and the
     * effects the BSS confirmed, for the action-claim gate (§12).
     *
     * @param doneEffects {@code ActionEffect} names with a {@code DONE} step, for example {@code UNSUBSCRIBE}
     */
    public record Digest(String text, Set<String> doneEffects) {

        public boolean isEmpty() {
            return text == null;
        }
    }

    public Digest digest(AccountId account, UUID conversationId) {
        List<ActionView> all = actions.forConversation(account, conversationId);
        if (all.isEmpty()) {
            return new Digest(null, Set.of());
        }
        StringBuilder text = new StringBuilder("Proposals and actions in this conversation (current status;"
                + " describe them exactly as stated here):");
        Set<String> done = new LinkedHashSet<>();
        for (ActionView a : all) {
            text.append("\n- Action ").append(a.actionId()).append(' ').append(a.type()).append(": ").append(a.summary());
            if (a.amount() != null) {
                text.append(", ").append(a.amount().display());
            }
            text.append(". Status ").append(a.status()).append(". ").append(a.message());
            a.execution()
                .stream()
                .filter(s -> s.status() == StepStatus.DONE)
                .forEach(s -> done.add(s.effect().name()));
        }
        return new Digest(text.toString(), Set.copyOf(done));
    }

    /**
     * SPEC §4.5 (at most 8 tool calls per turn, then escalate). The orchestrator escalates itself and
     * does not rely on the model (actions.md §3.4). At most once per conversation (dedupe).
     *
     * @param summary masked server text built from the diagnosis
     */
    public Optional<ActionEvent> escalateForToolBudget(AccountId account, UUID conversationId, String summary) {
        ProposalResult result = actions.propose(account, conversationId,
                new ActionRequest.Escalation(summary, "TOOL_BUDGET_EXHAUSTED"));
        return result.action().map(ActionEvent::of);
    }
}
