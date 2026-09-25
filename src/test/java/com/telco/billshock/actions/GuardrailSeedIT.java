package com.telco.billshock.actions;

import com.telco.billshock.actions.ActionRequest.Dispute;
import com.telco.billshock.actions.ActionRequest.GoodwillCredit;
import com.telco.billshock.actions.ActionRequest.VasUnsubscribe;
import com.telco.billshock.actions.GuardrailDecision.Outcome;
import com.telco.billshock.actions.GuardrailDecision.ReasonCode;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.Money;
import com.telco.billshock.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The guardrails on the real seed, with the context built from the real read models (deterministic-core.md §4). */
@IntegrationTest
class GuardrailSeedIT {

    @Autowired
    GuardrailService guardrails;

    @Test
    void astroDailyIsRefundedInFull() {
        GuardrailDecision d = guardrails.evaluate(AccountId.of(1003), new VasUnsubscribe("SUB-1003-VAS-ASTRO", true));

        assertThat(d.outcome()).isEqualTo(Outcome.PROPOSE);
        assertThat(d.amountExclGst()).isEqualTo(Money.of("196.00"));
        assertThat(d.amountInclGst()).isEqualTo(Money.of("231.28"));
        assertThat(d.lineItemIds()).containsExactly(1003260903L, 1003260904L, 1003260905L, 1003260906L);
        assertThat(d.refundAmountIncomplete()).isFalse();
    }

    @Test
    void cricketScoresIsNotRefunded() {
        assertThat(guardrails.evaluate(AccountId.of(1003), new VasUnsubscribe("SUB-1003-VAS-CRICKET", true)).reasons())
            .containsExactly(ReasonCode.REFUND_NOT_ELIGIBLE_OPT_IN_PRESENT);
    }

    @Test
    void theDuplicateIsDisputedNotCredited() {
        assertThat(guardrails.evaluate(AccountId.of(1005),
                new GoodwillCredit(Money.of("599.00"), "duplicate", List.of(1005260902L))).reasons())
            .containsExactly(ReasonCode.USE_DISPUTE);
        assertThat(guardrails.evaluate(AccountId.of(1005), new Dispute(List.of(1005260902L), "duplicate"))
            .amountInclGst()).isEqualTo(Money.of("706.82"));
    }

    @Test
    void goodwillWithinFifteenPercentOfTheRoamingBill() {
        GuardrailDecision d = guardrails.evaluate(AccountId.of(1001),
                new GoodwillCredit(Money.of("371.10"), "roaming", List.of()));

        assertThat(d.amountInclGst()).isEqualTo(Money.of("437.90"));
        assertThat(d.autoApprovalEligible()).isTrue();
        assertThat(d.autoApprove()).isFalse(); // Level 1 in the MVP slice
    }

    @Test
    void anotherAccountsLineItemIsUnknown() {
        assertThat(guardrails.evaluate(AccountId.of(1002), new Dispute(List.of(1005260902L), "not mine")).reasons())
            .containsExactly(ReasonCode.UNKNOWN_LINE_ITEM);
    }
}
