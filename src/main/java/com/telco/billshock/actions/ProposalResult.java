package com.telco.billshock.actions;

import java.util.List;
import java.util.Optional;

import com.telco.billshock.actions.GuardrailDecision.ReasonCode;

/**
 * What {@link ProposedActionService#propose} did (actions.md §3.2).
 *
 * @param action the new or existing action; empty for {@link Kind#NOT_POSSIBLE}
 * @param reasons the guardrail reason codes: for the caller's wording only, never shown to the model or customer
 */
public record ProposalResult(Kind kind, Optional<ActionView> action, List<ReasonCode> reasons) {

    public ProposalResult {
        reasons = List.copyOf(reasons);
    }

    public enum Kind {
        /** A new {@code PENDING_CONFIRMATION} action. */
        PROPOSED,
        /** A live or executed action for the same target already exists; it is returned instead (§5.3). */
        ALREADY_PROPOSED,
        /** A new {@code ESCALATED} action; its ticket was opened if the BSS answered (§6.5). */
        ESCALATED,
        /** The guardrails rejected it; nothing was created. */
        NOT_POSSIBLE
    }
}
