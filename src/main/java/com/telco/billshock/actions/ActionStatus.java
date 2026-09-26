package com.telco.billshock.actions;

import java.util.Set;

/** {@code proposed_action.status}: the ADR-004 state machine as amended in actions.md §2. */
public enum ActionStatus {
    PENDING_CONFIRMATION, ESCALATED, AWAITING_SUPERVISOR, APPROVED, AUTO_APPROVED, REJECTED, EXPIRED, EXECUTING,
    EXECUTED, FAILED;

    /** A live proposal: it still holds its target (actions.md §5.3). */
    public static final Set<ActionStatus> OPEN = Set.of(PENDING_CONFIRMATION, AWAITING_SUPERVISOR, APPROVED,
            AUTO_APPROVED, EXECUTING, ESCALATED);
}
