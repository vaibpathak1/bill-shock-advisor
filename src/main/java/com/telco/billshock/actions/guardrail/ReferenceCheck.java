package com.telco.billshock.actions.guardrail;

import com.telco.billshock.actions.ActionRequest;
import com.telco.billshock.actions.GuardrailDecision.ReasonCode;
import com.telco.billshock.bss.BillingReadModel.LineItem;
import com.telco.billshock.bss.ProductInventoryGateway.ProductType;

import java.util.List;
import java.util.Objects;

/**
 * Every id in the request must exist under the caller's account (deterministic-core.md
 * §4.1): the context holds only that account's data, so another account's id is not found.
 */
final class ReferenceCheck implements GuardrailCheck {

    @Override
    public void apply(ActionRequest request, GuardrailContext context, DecisionBuilder decision) {
        List<Long> cited = GuardrailContextFactory.citedLineItemIds(request);
        if (!cited.isEmpty()) {
            if (!context.lineItems().keySet().containsAll(cited)) {
                decision.reject(ReasonCode.UNKNOWN_LINE_ITEM);
                return;
            }
            List<LineItem> lines = cited.stream().map(context.lineItems()::get).toList();
            if (lines.stream().map(LineItem::billId).distinct().count() > 1) {
                decision.reject(ReasonCode.LINE_ITEMS_ON_SEVERAL_BILLS);
                return;
            }
            if (lines.stream().anyMatch(l -> "TAX".equals(l.category()))) {
                decision.reject(ReasonCode.TAX_LINE_CITED);
                return;
            }
        }
        switch (request) {
            case ActionRequest.GoodwillCredit credit when context.bill().isEmpty() ->
                decision.reject(ReasonCode.NO_BILL);
            case ActionRequest.VasUnsubscribe vas when context.product(vas.subscriptionId()).isEmpty() ->
                decision.reject(ReasonCode.UNKNOWN_SUBSCRIPTION);
            case ActionRequest.PlanChange change -> {
                if (context.plans().stream().noneMatch(p -> p.code().equals(change.planCode()))) {
                    decision.reject(ReasonCode.UNKNOWN_PLAN);
                }
                else if (context.products()
                    .stream()
                    .anyMatch(p -> p.type() == ProductType.PLAN && Objects.equals(p.code(), change.planCode()))) {
                    decision.reject(ReasonCode.ALREADY_ON_PLAN);
                }
            }
            case ActionRequest.AddOnPurchase addOn
                    when context.addOns().stream().noneMatch(a -> a.code().equals(addOn.addOnCode())) ->
                decision.reject(ReasonCode.UNKNOWN_ADD_ON);
            default -> {
            }
        }
    }
}
