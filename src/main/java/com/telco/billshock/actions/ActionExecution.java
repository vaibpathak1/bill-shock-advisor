package com.telco.billshock.actions;

/**
 * System re-drive of an action whose outcome is unknown (actions.md §6.3): an {@code EXECUTING}
 * action runs its steps again with the same BSS key, and an {@code ESCALATED} action without a
 * ticket tries to open it again. Used by tests in 6a and by the reconcile job in 6b (§6.4).
 * Never moves an action to {@code FAILED} unless the BSS definitely rejects a step.
 */
public interface ActionExecution {

    /** @throws IllegalStateException if the action is in no state that can be re-driven */
    ActionView redrive(long actionId);
}
