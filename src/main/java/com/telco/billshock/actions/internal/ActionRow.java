package com.telco.billshock.actions.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.telco.billshock.actions.ActionEffect;
import com.telco.billshock.actions.ActionStatus;
import com.telco.billshock.actions.ActionType;
import com.telco.billshock.actions.ExecutionStep;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.Money;

/** One {@code proposed_action} row (V5). */
record ActionRow(long actionId, AccountId account, UUID conversationId, ActionType type, ActionStatus status,
        Money amount, Money gstAmount, ActionParams params, boolean requiresSupervisor, String targetRef,
        List<ExecutionStep> execution, String idempotencyKey, Instant expiresAt, String decidedBy, Instant decidedAt,
        Instant executedAt, String externalRef, String failureReason, int version, Instant createdAt) {

    ActionRow {
        execution = List.copyOf(execution);
    }

    /** The key sent with every BSS call of this action; the BSS deduplicates on it (ADR-004). */
    String bssKey() {
        return "pa-" + actionId;
    }

    Money amountInclGst() {
        return amount == null ? null : amount.plus(gstAmount);
    }

    boolean stepDone(ActionEffect effect) {
        return execution.stream().anyMatch(s -> s.effect() == effect && s.status() == ExecutionStep.StepStatus.DONE);
    }
}
