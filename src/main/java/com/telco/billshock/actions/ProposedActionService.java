package com.telco.billshock.actions;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.telco.billshock.domain.AccountId;

/**
 * The ProposedAction workflow (ADR-004; actions.md). Every method takes the account from the
 * caller's SecurityContext (SPEC §4.3 rule 2) and sees only that account's actions. Proposing
 * never executes anything (rule 3); execution happens only through {@link #confirm}.
 */
public interface ProposedActionService {

    /**
     * Runs the guardrails and creates a {@code PENDING_CONFIRMATION} or {@code ESCALATED} action, or
     * returns the live action that already holds the same target (§5.3). An escalation opens its
     * ticket at once (§6.5).
     */
    ProposalResult propose(AccountId account, UUID conversationId, ActionRequest request);

    /**
     * {@code POST /actions/{id}/confirm}: idempotent per key (§6.6); re-checks the guardrails and, if
     * they still pass with the same amount, executes (§6.1).
     *
     * @param actorRef the customer's username, for {@code decided_by} and the audit (A-116)
     * @param requestHash {@link IdempotencyKeys#requestHash} of the HTTP request
     */
    CommandResponse confirm(AccountId account, String actorRef, long actionId, String idempotencyKey,
            String requestHash);

    /** {@code POST /actions/{id}/reject}; idempotent per key. @param reason free text, PII-scrubbed here */
    CommandResponse reject(AccountId account, String actorRef, long actionId, String idempotencyKey,
            String requestHash, String reason);

    /** Newest first; marks expired proposals {@code EXPIRED} first (A-111). @param status {@code null} = all */
    ActionPage list(AccountId account, ActionStatus status, int page, int size);

    /** The conversation's actions, oldest first (for the SSE event and the digest, §3.3, §3.5). */
    List<ActionView> forConversation(AccountId account, UUID conversationId);

    Optional<ActionView> find(AccountId account, long actionId);
}
