package com.telco.billshock.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.telco.billshock.actions.ActionStatus;
import com.telco.billshock.actions.ActionType;
import com.telco.billshock.actions.ActionView;
import com.telco.billshock.actions.GuardrailDecision.ReasonCode;
import com.telco.billshock.actions.ProposalResult;

/** What the action tools tell the model (actions.md §3.2). */
class ActionToolsTest {

    @ParameterizedTest
    @ValueSource(strings = { "876.00", "876", "₹876.00 excl. GST", " 876.0 " })
    void aCopiedAmountIsAccepted(String text) {
        assertThat(ActionTools.parseAmount(text).amount()).isEqualByComparingTo("876.00");
    }

    @Test
    void anythingElseIsNotAnAmount() {
        assertThatThrownBy(() -> ActionTools.parseAmount("about 800")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActionTools.parseAmount("876.001")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActionTools.parseAmount(null)).isInstanceOf(IllegalArgumentException.class);
    }

    /** SPEC §4.5: the model never learns a threshold or a reason code. */
    @ParameterizedTest
    @EnumSource(ReasonCode.class)
    void aRefusalNamesNoThresholdAndNoCode(ReasonCode reason) {
        String message = ActionTools.notPossible(List.of(reason));
        assertThat(message).doesNotContainPattern("\\d").doesNotContain("₹", "%", "supervisor", "limit");
        Arrays.stream(ReasonCode.values()).forEach(code -> assertThat(message).doesNotContain(code.name()));
    }

    @Test
    void aSupervisorFlaggedProposalSaysSoWithoutSayingWhy() {
        ActionView view = new ActionView(42, ActionType.GOODWILL_CREDIT, ActionStatus.PENDING_CONFIRMATION,
                "Goodwill credit on the roaming charges of your September 2026 bill",
                new ActionView.AmountView("1033.68", "₹1,033.68 incl. GST"), "₹876.00 excl. GST", "₹157.68 GST", true,
                UUID.randomUUID(), null, null, null, null, null, List.of(), "Waiting for your confirmation.");
        ActionToolResult r = (ActionToolResult) ActionTools.result(new ProposalResult(ProposalResult.Kind.PROPOSED,
                Optional.of(view), List.of(ReasonCode.ABOVE_BILL_SHARE, ReasonCode.ABOVE_AUTO_APPROVAL_LIMIT)));

        assertThat(r.status()).isEqualTo("PROPOSED");
        assertThat(r.amount().display()).isEqualTo("₹1,033.68 incl. GST");
        assertThat(r.needsCustomerConfirmation()).isTrue();
        assertThat(r.needsSupervisorReview()).isTrue();
        assertThat(r.message()).contains("Nothing has changed yet").contains("a supervisor reviews it")
            .doesNotContain("ABOVE", "15", "500");
    }
}
