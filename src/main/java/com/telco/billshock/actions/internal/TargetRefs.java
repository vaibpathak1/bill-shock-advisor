package com.telco.billshock.actions.internal;

import java.util.UUID;
import java.util.stream.Collectors;

import com.telco.billshock.actions.ActionRequest;
import com.telco.billshock.actions.GuardrailDecision;

/**
 * {@code proposed_action.target_ref}: what an action acts on, for the dedupe index (actions.md
 * §5.3, owner answers 2 and 3). Built from server facts (the guardrail decision) and the ids the
 * guardrails already validated.
 */
final class TargetRefs {

    private TargetRefs() {
    }

    static String of(ActionRequest request, GuardrailDecision decision, UUID conversationId) {
        return switch (request) {
            case ActionRequest.GoodwillCredit c -> "goodwill:" + decision.billPeriod();
            case ActionRequest.VasUnsubscribe v -> "vas:" + v.subscriptionId();
            case ActionRequest.ThirdPartyBarring b -> "barring";
            case ActionRequest.PlanChange p -> "plan-change";
            case ActionRequest.AddOnPurchase a -> "add-on:" + a.addOnCode();
            case ActionRequest.Dispute d -> "dispute:" + d.lineItemIds()
                .stream()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
            case ActionRequest.Escalation e -> "escalation:" + conversationId;
        };
    }
}
