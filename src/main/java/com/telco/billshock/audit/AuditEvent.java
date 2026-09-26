package com.telco.billshock.audit;

import java.util.Objects;
import java.util.UUID;

import com.telco.billshock.domain.AccountId;

/**
 * One row of the append-only audit log (SPEC §4.3 rule 5; actions.md §7). The time, correlation
 * id and prompt version are added by the writer.
 *
 * @param account the account concerned, or {@code null}
 * @param actorRef who acted: the principal's username for a customer, never an MSISDN (A-116); {@code null}
 *        for the agent and the system
 * @param eventType for example {@code ACTION_CONFIRMED} (actions.md §7)
 * @param payloadJson a JSON object; free text in it is already PII-scrubbed by the caller
 */
public record AuditEvent(AccountId account, UUID conversationId, ActorType actor, String actorRef, String eventType,
        String toolName, Long actionId, String payloadJson, String resultHash) {

    public AuditEvent {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(eventType, "eventType");
        payloadJson = payloadJson == null ? "{}" : payloadJson;
    }

    /** An event about an action. */
    public static AuditEvent action(AccountId account, UUID conversationId, ActorType actor, String actorRef,
            String eventType, long actionId, String payloadJson) {
        return new AuditEvent(account, conversationId, actor, actorRef, eventType, null, actionId, payloadJson, null);
    }

    /** {@code audit_events.actor_type}. */
    public enum ActorType {
        CUSTOMER, CARE_AGENT, SUPERVISOR, ADMIN, AGENT, SYSTEM
    }
}
