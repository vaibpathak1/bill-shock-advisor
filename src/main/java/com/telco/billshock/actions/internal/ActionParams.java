package com.telco.billshock.actions.internal;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.telco.billshock.actions.ActionRequest;
import com.telco.billshock.actions.ActionType;
import com.telco.billshock.domain.Money;

/**
 * {@code proposed_action.params}: the request as the LLM chose it, plus server facts for the
 * summary (actions.md §5.1). Free text is PII-scrubbed and cut to 200 characters before it gets
 * here. The confirm-time re-check rebuilds the {@link ActionRequest} from it.
 *
 * @param billPeriod the bill the action concerns ({@code YYYY-MM}), from the guardrail decision
 * @param productName the VAS name from TMF622 (sanitised), for the summary
 * @param categories the categories of the cited line items (server data), for the summary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ActionParams(String billPeriod, List<Long> lineItemIds, String amountExclGst, String reason,
        String subscriptionId, Boolean requestRefund, String productName, String planCode, String effective,
        String addOnCode, String summary, List<String> categories) {

    static ActionParams of(ActionRequest request, String billPeriod, String productName, List<String> categories,
            String scrubbedReason, String scrubbedSummary) {
        return switch (request) {
            case ActionRequest.GoodwillCredit c -> new ActionParams(billPeriod, c.lineItemIds(),
                    c.amountExclGst().amount().toPlainString(), scrubbedReason, null, null, null, null, null, null,
                    null, categories);
            case ActionRequest.VasUnsubscribe v -> new ActionParams(billPeriod, null, null, null, v.subscriptionId(),
                    v.requestRefund(), productName, null, null, null, null, null);
            case ActionRequest.ThirdPartyBarring b -> new ActionParams(null, null, null, null, null, null, null, null,
                    null, null, null, null);
            case ActionRequest.PlanChange p -> new ActionParams(null, null, null, null, null, null, null, p.planCode(),
                    p.effective().name(), null, null, null);
            case ActionRequest.AddOnPurchase a -> new ActionParams(null, null, null, null, null, null, null, null, null,
                    a.addOnCode(), null, null);
            case ActionRequest.Dispute d -> new ActionParams(billPeriod, d.lineItemIds(), null, scrubbedReason, null,
                    null, null, null, null, null, null, categories);
            case ActionRequest.Escalation e -> new ActionParams(billPeriod, null, null, scrubbedReason, null, null,
                    null, null, null, null, scrubbedSummary, null);
        };
    }

    ActionRequest toRequest(ActionType type) {
        return switch (type) {
            case GOODWILL_CREDIT -> new ActionRequest.GoodwillCredit(Money.of(amountExclGst), reason, lineItemIds);
            case VAS_UNSUBSCRIBE -> new ActionRequest.VasUnsubscribe(subscriptionId, Boolean.TRUE.equals(requestRefund));
            case THIRD_PARTY_BARRING -> new ActionRequest.ThirdPartyBarring();
            case PLAN_CHANGE -> new ActionRequest.PlanChange(planCode, ActionRequest.Effective.valueOf(effective));
            case ADD_ON -> new ActionRequest.AddOnPurchase(addOnCode);
            case DISPUTE -> new ActionRequest.Dispute(lineItemIds, reason);
            case ESCALATION -> new ActionRequest.Escalation(summary, reason);
        };
    }
}
