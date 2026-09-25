package com.telco.billshock.actions.guardrail;

import com.telco.billshock.actions.ActionRequest;
import com.telco.billshock.actions.GuardrailDecision.ReasonCode;

/** A request to hand off to a human is an escalation (US-RES-06). */
final class EscalationCheck implements GuardrailCheck {

    @Override
    public void apply(ActionRequest request, GuardrailContext context, DecisionBuilder decision) {
        if (request instanceof ActionRequest.Escalation) {
            decision.escalate(ReasonCode.ESCALATION_REQUESTED);
        }
    }
}
