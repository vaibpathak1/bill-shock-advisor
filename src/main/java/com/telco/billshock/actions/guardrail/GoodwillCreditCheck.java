package com.telco.billshock.actions.guardrail;

import com.telco.billshock.actions.ActionRequest;
import com.telco.billshock.actions.GuardrailDecision.ReasonCode;
import com.telco.billshock.actions.GuardrailProperties;
import com.telco.billshock.bss.BillingReadModel.BillSummary;
import com.telco.billshock.domain.GstCalculator;
import com.telco.billshock.domain.Money;

/**
 * Goodwill credit (SPEC §4.5; deterministic-core.md §4.5). All tests are on the
 * GST-inclusive amount (Q-20), and all limits are inclusive (A-84).
 */
final class GoodwillCreditCheck implements GuardrailCheck {

    private final GuardrailProperties.Goodwill limits;
    private final MoneyOutCap cap;
    private final GstCalculator gst;

    GoodwillCreditCheck(GuardrailProperties.Goodwill limits, MoneyOutCap cap, GstCalculator gst) {
        this.limits = limits;
        this.cap = cap;
        this.gst = gst;
    }

    @Override
    public void apply(ActionRequest request, GuardrailContext context, DecisionBuilder decision) {
        if (!(request instanceof ActionRequest.GoodwillCredit credit)) {
            return;
        }
        BillSummary bill = context.bill().orElseThrow();
        // A-94 with Q-20: the tool passes the amount before GST; every threshold below is compared
        // with the GST-inclusive amount, computed here with the bill-level rule for the bill's supply type.
        Money exclGst = credit.amountExclGst();
        Money gstAmount = gst.compute(exclGst, bill.supplyType()).total();
        Money inclGst = exclGst.plus(gstAmount);
        decision.amount(bill.billPeriod(), exclGst, gstAmount, credit.lineItemIds());

        if (!inclGst.isPositive()) {
            decision.reject(ReasonCode.INVALID_AMOUNT);
            return;
        }
        // US-RES-03: a billing error is disputed, not compensated with goodwill.
        if (credit.lineItemIds().stream().anyMatch(context.duplicateLineItemIds()::contains)) {
            decision.reject(ReasonCode.USE_DISPUTE);
            return;
        }
        if (inclGst.compareTo(bill.total()) > 0) {
            decision.reject(ReasonCode.EXCEEDS_BILL);
            return;
        }
        if (cap.escalateIfAbove(inclGst, decision)) {
            return;
        }
        boolean withinPolicy = true;
        if (inclGst.compareTo(bill.total().percent(limits.autoApprovalMaxBillSharePct())) > 0) {
            decision.requireSupervisor(ReasonCode.ABOVE_BILL_SHARE);
            withinPolicy = false;
        }
        if (inclGst.amount().compareTo(limits.autoApprovalMaxInr()) > 0) {
            decision.requireSupervisor(ReasonCode.ABOVE_AUTO_APPROVAL_LIMIT);
            withinPolicy = false;
        }
        if (context.priorCredit()) {
            decision.requireSupervisor(ReasonCode.PRIOR_CREDIT);
            withinPolicy = false;
        }
        decision.reason(ReasonCode.CONFIRMATION_REQUIRED);
        decision.autoApprovalEligible(withinPolicy);
    }
}
