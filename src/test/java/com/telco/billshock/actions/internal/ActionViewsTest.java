package com.telco.billshock.actions.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.telco.billshock.actions.ActionEffect;
import com.telco.billshock.actions.ActionStatus;
import com.telco.billshock.actions.ActionType;
import com.telco.billshock.actions.ActionView;
import com.telco.billshock.actions.ExecutionStep;
import com.telco.billshock.actions.ExecutionStep.StepStatus;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.Money;

/** Server wording of summaries and status messages (actions.md §3.3, §4, §12). */
class ActionViewsTest {

    private static final ActionParams ASTRO = new ActionParams("2026-09", null, null, null, "SUB-1003-VAS-ASTRO", true,
            "Astro Daily", null, null, null, null, null);

    private static ActionRow row(ActionStatus status, List<ExecutionStep> steps, String failureReason) {
        return new ActionRow(7, AccountId.of(1003), null, ActionType.VAS_UNSUBSCRIBE, status, Money.of("196.00"),
                Money.of("35.28"), ASTRO, false, "vas:SUB-1003-VAS-ASTRO", steps, "pa-7", null, "cust1003", null, null,
                null, failureReason, 3, Instant.parse("2026-09-26T04:00:00Z"));
    }

    @Test
    void aPartialVasSaysWhatWasDoneAndWhatWasNot() {
        ActionView v = ActionViews.view(row(ActionStatus.FAILED, List.of(
                new ExecutionStep(ActionEffect.UNSUBSCRIBE, StepStatus.DONE, "MOCK-ORD-1", null, null),
                new ExecutionStep(ActionEffect.REFUND, StepStatus.REJECTED, null, "NOT_REFUNDABLE", null)), "BSS_REJECTED"));

        assertThat(v.message()).isEqualTo("The subscription is cancelled. The refund could not be processed"
                + " automatically; our billing team will handle it manually.");
        assertThat(v.summary()).isEqualTo("Unsubscribe from Astro Daily and refund its charges");
        assertThat(v.amount().display()).isEqualTo("₹231.28 incl. GST");
        assertThat(v.createdAt()).isEqualTo("2026-09-26T09:30+05:30");
    }

    @Test
    void aFailureWithNothingDoneSaysNothingChanged() {
        ActionView v = ActionViews.view(row(ActionStatus.FAILED, List.of(
                new ExecutionStep(ActionEffect.UNSUBSCRIBE, StepStatus.REJECTED, null, "UNKNOWN_SUBSCRIPTION", null)),
                "BSS_REJECTED"));
        assertThat(v.message()).isEqualTo("The billing system could not complete this. Nothing was changed on your"
                + " account.");
    }

    @Test
    void aChangedAmountOffersAFreshProposal() {
        assertThat(ActionViews.view(row(ActionStatus.REJECTED, List.of(), ActionViews.AMOUNT_CHANGED)).message())
            .isEqualTo("The amount has changed since this was proposed. Nothing was changed on your account. Ask for a"
                    + " fresh proposal.");
    }

    @Test
    void untrustedProductNamesAreCleaned() {
        assertThat(ActionViews.name("Astro\u0000 Daily\n")).isEqualTo("Astro  Daily");
        assertThat(ActionViews.name("x".repeat(80))).hasSize(60);
        assertThat(ActionViews.name(null)).isEqualTo("this service");
    }
}
