package com.telco.billshock.actions.guardrail;

import com.telco.billshock.actions.ActionRequest;
import com.telco.billshock.actions.GuardrailDecision.ReasonCode;
import com.telco.billshock.bss.BillingReadModel.BillSummary;
import com.telco.billshock.bss.BillingReadModel.LineItem;
import com.telco.billshock.bss.ProductInventoryGateway.ActiveProduct;
import com.telco.billshock.bss.ProductInventoryGateway.OptInEvidence;
import com.telco.billshock.bss.ProductInventoryGateway.ProductType;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.GstCalculator;
import com.telco.billshock.domain.Money;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * VAS unsubscribe, with or without a refund (SPEC §4.5, US-RES-02; deterministic-core.md
 * §4.6). A refund is allowed only without a complete double opt-in, and its amount is
 * computed here from the local bill history: the LLM never supplies it. A refund is exempt
 * from the goodwill bill-share and prior-credit rules, but not from the money-out cap.
 */
final class VasCheck implements GuardrailCheck {

    private final MoneyOutCap cap;
    private final GstCalculator gst;

    VasCheck(MoneyOutCap cap, GstCalculator gst) {
        this.cap = cap;
        this.gst = gst;
    }

    @Override
    public void apply(ActionRequest request, GuardrailContext context, DecisionBuilder decision) {
        if (!(request instanceof ActionRequest.VasUnsubscribe vas)) {
            return;
        }
        ActiveProduct product = context.product(vas.subscriptionId()).orElseThrow();
        if (product.type() != ProductType.VAS) {
            decision.reject(ReasonCode.NOT_A_VAS);
            return;
        }
        if (!vas.requestRefund()) {
            decision.reason(ReasonCode.CONFIRMATION_REQUIRED);
            return;
        }
        if (product.optInEvidence().map(OptInEvidence::isDoubleOptIn).orElse(false)) {
            decision.reject(ReasonCode.REFUND_NOT_ELIGIBLE_OPT_IN_PRESENT);
            return;
        }

        List<LineItem> charges = context.lineItems()
            .values()
            .stream()
            .filter(l -> vas.subscriptionId().equals(l.subscriptionId()) && !"TAX".equals(l.category()))
            .sorted(Comparator.comparingLong(LineItem::lineItemId))
            .toList();
        boolean predatesHistory = context.bills()
            .stream()
            .map(BillSummary::periodStart)
            .min(Comparator.naturalOrder())
            .map(start -> product.activatedOn() == null || product.activatedOn().isBefore(start))
            .orElse(true);
        if (charges.isEmpty() && !predatesHistory) {
            decision.reject(ReasonCode.NO_REFUNDABLE_CHARGES);
            return;
        }

        // GST per bill, under that bill's supply type (Q-20), then summed.
        Money exclGst = Money.ZERO;
        Money gstAmount = Money.ZERO;
        Map<Long, List<LineItem>> byBill = charges.stream().collect(Collectors.groupingBy(LineItem::billId));
        for (Map.Entry<Long, List<LineItem>> entry : byBill.entrySet()) {
            BillSummary bill = context.billById(entry.getKey()).orElseThrow();
            Money billCharges = entry.getValue().stream().map(LineItem::amount).reduce(Money.ZERO, Money::plus);
            exclGst = exclGst.plus(billCharges);
            gstAmount = gstAmount.plus(gst.compute(billCharges, bill.supplyType()).total());
        }
        BillPeriod latestPeriod = charges.stream()
            .map(LineItem::billPeriod)
            .max(Comparator.naturalOrder())
            .orElse(context.bill().map(BillSummary::billPeriod).orElse(null));
        decision.amount(latestPeriod, exclGst, gstAmount, charges.stream().map(LineItem::lineItemId).toList());
        if (predatesHistory) {
            decision.refundAmountIncomplete();
        }
        if (cap.escalateIfAbove(exclGst.plus(gstAmount), decision)) {
            return;
        }
        decision.reason(ReasonCode.CONFIRMATION_REQUIRED);
    }
}
