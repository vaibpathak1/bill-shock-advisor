package com.telco.billshock.actions.guardrail;

import com.telco.billshock.actions.ActionRequest;
import com.telco.billshock.actions.GuardrailDecision.ReasonCode;

/**
 * Level 0 = explanations only. The action tools are not even registered then (ADR-004);
 * this is defence in depth.
 */
final class AutonomyCheck implements GuardrailCheck {

    @Override
    public void apply(ActionRequest request, GuardrailContext context, DecisionBuilder decision) {
        if (context.autonomyLevel() == 0 && !(request instanceof ActionRequest.Escalation)) {
            decision.reject(ReasonCode.ACTIONS_DISABLED);
        }
    }
}
