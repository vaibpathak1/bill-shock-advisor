package com.telco.billshock.actions.guardrail;

import com.telco.billshock.actions.GuardrailDecision;
import com.telco.billshock.actions.GuardrailDecision.Outcome;
import com.telco.billshock.actions.GuardrailDecision.ReasonCode;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.Money;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the checks' verdicts. {@link #reject} and {@link #escalate} are terminal: the
 * chain stops. Flags and reasons accumulate otherwise.
 */
public final class DecisionBuilder {

    private Outcome outcome = Outcome.PROPOSE;
    private final List<ReasonCode> reasons = new ArrayList<>();
    private boolean supervisorRequired;
    private boolean autoApprovalEligible;
    private BillPeriod billPeriod;
    private Money amountExclGst;
    private Money gstAmount;
    private List<Long> lineItemIds = List.of();
    private boolean refundAmountIncomplete;

    public void reject(ReasonCode reason) {
        outcome = Outcome.REJECT;
        reasons.add(reason);
    }

    public void escalate(ReasonCode reason) {
        outcome = Outcome.ESCALATE;
        reasons.add(reason);
    }

    public boolean isTerminal() {
        return outcome != Outcome.PROPOSE;
    }

    public void requireSupervisor(ReasonCode reason) {
        supervisorRequired = true;
        reasons.add(reason);
    }

    public void reason(ReasonCode reason) {
        reasons.add(reason);
    }

    public void autoApprovalEligible(boolean eligible) {
        autoApprovalEligible = eligible;
    }

    public void amount(BillPeriod billPeriod, Money exclGst, Money gst, List<Long> lineItemIds) {
        this.billPeriod = billPeriod;
        this.amountExclGst = exclGst;
        this.gstAmount = gst;
        this.lineItemIds = List.copyOf(lineItemIds);
    }

    public void refundAmountIncomplete() {
        refundAmountIncomplete = true;
        reasons.add(ReasonCode.REFUND_AMOUNT_INCOMPLETE);
    }

    GuardrailDecision build(int autonomyLevel) {
        boolean eligible = outcome == Outcome.PROPOSE && autoApprovalEligible && !supervisorRequired;
        return new GuardrailDecision(outcome, reasons, supervisorRequired, eligible, eligible && autonomyLevel >= 2,
                billPeriod, amountExclGst, gstAmount, amountExclGst == null ? null : amountExclGst.plus(gstAmount),
                lineItemIds, refundAmountIncomplete);
    }
}
