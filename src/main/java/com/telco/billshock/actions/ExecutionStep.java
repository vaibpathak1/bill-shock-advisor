package com.telco.billshock.actions;

import java.time.Instant;
import java.util.Objects;

/**
 * One BSS call of an action and what came of it (the {@code proposed_action.execution} array,
 * actions.md §6.2).
 *
 * @param reference the BSS receipt id (order, adjustment or ticket), or {@code null}
 * @param detail the BSS rejection code or the failure class, or {@code null}
 */
public record ExecutionStep(ActionEffect effect, StepStatus status, String reference, String detail, Instant at) {

    public ExecutionStep {
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(status, "status");
    }

    public enum StepStatus {
        /** The BSS confirmed it. */
        DONE,
        /** The BSS definitely did not apply it. */
        REJECTED,
        /** Timeout, connection error or unknown result: it may have been applied (A-112). */
        UNKNOWN
    }
}
