package com.telco.billshock.actions.guardrail;

import com.telco.billshock.actions.GuardrailDecision.ReasonCode;
import com.telco.billshock.domain.Money;

import java.math.BigDecimal;

/**
 * The absolute cap on every action that pays money out (goodwill credit, VAS refund): above
 * it, incl. GST, the proposal is escalated to a human. The limit is inclusive (A-84). No
 * money-out action is uncapped (gate review 4a, item 4).
 */
final class MoneyOutCap {

    private final BigDecimal escalateAboveInr;

    MoneyOutCap(BigDecimal escalateAboveInr) {
        this.escalateAboveInr = escalateAboveInr;
    }

    /** @return {@code true} if the decision was escalated (terminal) */
    boolean escalateIfAbove(Money amountInclGst, DecisionBuilder decision) {
        if (amountInclGst.amount().compareTo(escalateAboveInr) > 0) {
            decision.escalate(ReasonCode.MONEY_OUT_ABOVE_ESCALATION_LIMIT);
            return true;
        }
        return false;
    }
}
