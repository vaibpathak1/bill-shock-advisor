package com.telco.billshock.actions.guardrail;

import com.telco.billshock.actions.ActionRequest;
import com.telco.billshock.actions.GuardrailDecision.ReasonCode;
import com.telco.billshock.bss.BillingReadModel.BillSummary;
import com.telco.billshock.domain.GstCalculator;
import com.telco.billshock.domain.Money;

/** A billing dispute (US-RES-03): the disputed amount is the sum of the cited lines, with GST. */
final class DisputeCheck implements GuardrailCheck {

    private final GstCalculator gst;

    DisputeCheck(GstCalculator gst) {
        this.gst = gst;
    }

    @Override
    public void apply(ActionRequest request, GuardrailContext context, DecisionBuilder decision) {
        if (!(request instanceof ActionRequest.Dispute dispute)) {
            return;
        }
        if (dispute.lineItemIds().isEmpty()) {
            decision.reject(ReasonCode.NO_LINE_ITEMS);
            return;
        }
        BillSummary bill = context.bill().orElseThrow();
        Money exclGst = dispute.lineItemIds()
            .stream()
            .map(id -> context.lineItems().get(id).amount())
            .reduce(Money.ZERO, Money::plus);
        decision.amount(bill.billPeriod(), exclGst, gst.compute(exclGst, bill.supplyType()).total(),
                dispute.lineItemIds());
        decision.reason(ReasonCode.CONFIRMATION_REQUIRED);
    }
}
