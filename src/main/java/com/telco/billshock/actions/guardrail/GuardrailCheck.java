package com.telco.billshock.actions.guardrail;

import com.telco.billshock.actions.ActionRequest;

/**
 * One handler of the guardrail chain (Chain of Responsibility, SPEC §4.2). A handler that
 * does not apply to the request does nothing.
 */
public interface GuardrailCheck {

    void apply(ActionRequest request, GuardrailContext context, DecisionBuilder decision);
}
