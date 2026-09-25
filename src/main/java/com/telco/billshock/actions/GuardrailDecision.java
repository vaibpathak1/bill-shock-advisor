package com.telco.billshock.actions;

import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.Money;

import java.util.List;

/**
 * The guardrail chain's decision on one {@link ActionRequest} (ADR-004,
 * deterministic-core.md §4.3). Reasons are codes, never thresholds: thresholds are not shown
 * to the LLM or the customer (SPEC §4.5).
 *
 * @param supervisorRequired the customer's confirmation is not enough; a supervisor must also approve
 * @param autoApprovalEligible the Level 2 auto-approval policy holds
 * @param autoApprove eligible and the autonomy level allows it; always {@code false} at Level 1
 * @param billPeriod the bill the action concerns, or {@code null}
 * @param amountExclGst the server-computed amount (credit, refund or disputed amount), or {@code null}
 * @param gstAmount GST on the amount under the bill's supply type (Q-20), or {@code null}
 * @param amountInclGst the GST-inclusive amount, or {@code null}
 * @param lineItemIds the line items the action covers
 * @param refundAmountIncomplete the subscription predates the local history: the full refund amount must come
 *        from BSS (A-86, limitation)
 */
public record GuardrailDecision(Outcome outcome, List<ReasonCode> reasons, boolean supervisorRequired,
        boolean autoApprovalEligible, boolean autoApprove, BillPeriod billPeriod, Money amountExclGst,
        Money gstAmount, Money amountInclGst, List<Long> lineItemIds, boolean refundAmountIncomplete) {

    public GuardrailDecision {
        reasons = List.copyOf(reasons);
        lineItemIds = List.copyOf(lineItemIds);
    }

    public enum Outcome {

        /** Create the proposal in {@code PENDING_CONFIRMATION}. */
        PROPOSE,

        /** Create it as {@code ESCALATED} and hand off to a human. */
        ESCALATE,

        /** Create nothing. */
        REJECT
    }

    public enum ReasonCode {
        ACTIONS_DISABLED,
        NO_BILL,
        UNKNOWN_LINE_ITEM,
        LINE_ITEMS_ON_SEVERAL_BILLS,
        TAX_LINE_CITED,
        UNKNOWN_SUBSCRIPTION,
        UNKNOWN_PLAN,
        UNKNOWN_ADD_ON,
        ALREADY_ON_PLAN,
        INVALID_AMOUNT,
        USE_DISPUTE,
        EXCEEDS_BILL,
        /** A money-out action (goodwill credit or VAS refund) above the cap, incl. GST. */
        MONEY_OUT_ABOVE_ESCALATION_LIMIT,
        ABOVE_BILL_SHARE,
        ABOVE_AUTO_APPROVAL_LIMIT,
        PRIOR_CREDIT,
        NOT_A_VAS,
        REFUND_NOT_ELIGIBLE_OPT_IN_PRESENT,
        NO_REFUNDABLE_CHARGES,
        REFUND_AMOUNT_INCOMPLETE,
        NO_LINE_ITEMS,
        CONFIRMATION_REQUIRED,
        ESCALATION_REQUESTED
    }
}
