package com.telco.billshock.actions.guardrail;

import com.telco.billshock.actions.ActionRequest;
import com.telco.billshock.actions.GuardrailDecision.ReasonCode;

/** A plan change always needs the customer's explicit confirmation, at every level (SPEC §4.5). */
final class PlanChangeCheck implements GuardrailCheck {

    @Override
    public void apply(ActionRequest request, GuardrailContext context, DecisionBuilder decision) {
        if (request instanceof ActionRequest.PlanChange) {
            decision.reason(ReasonCode.CONFIRMATION_REQUIRED);
            decision.autoApprovalEligible(false);
        }
    }
}
