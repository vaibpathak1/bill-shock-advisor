package com.telco.billshock.actions;

/**
 * One effect an action has at the BSS, recorded per execution step (actions.md §6.2). The
 * action-claim gate allows "it is done" wording per effect, only for a step that is
 * {@link ExecutionStep.StepStatus#DONE} (actions.md §12).
 */
public enum ActionEffect {
    CREDIT, REFUND, UNSUBSCRIBE, BARRING, PLAN_CHANGE, ADD_ON, DISPUTE, ESCALATION
}
