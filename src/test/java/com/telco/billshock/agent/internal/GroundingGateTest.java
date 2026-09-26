package com.telco.billshock.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.telco.billshock.agent.internal.GroundingGate.Verdict;

/** The sentence gate (llm-architecture.md §7; agent.md §8.1). */
class GroundingGateTest {

    private final GroundingGate gate = gate();

    private static GroundingGate gate() {
        GroundingGate g = new GroundingGate("BSA-REF-TEST");
        g.allow("""
                {"totalExcess":{"value":"2094.50","display":"₹2,094.50 incl. GST"},
                 "cause":{"display":"₹1,775.00 excl. GST"},"gst":{"display":"₹319.50 GST"},
                 "price":{"display":"₹599 + GST"},"negative":{"display":"-₹12.00 incl. GST"}}""");
        return g;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Your bill is ₹2,094.50 incl. GST higher than usual.",
            "Roaming was ₹1,775.00 excl. GST plus ₹319.50 GST.",
            "The plan costs ₹599 + GST a month.",
            "Your bill is ₹12.00 incl. GST lower.",
            "No amounts in this sentence, only 550 MB and 3 bills.",
            "" })
    void passesAmountsCopiedWithTheirLabel(String sentence) {
        GroundingGate.Result r = gate.check(sentence);
        assertThat(r.verdict()).isEqualTo(Verdict.PASS);
        assertThat(r.text()).isEqualTo(sentence);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Your bill is ₹2,000.00 incl. GST higher.",
            "In total that is ₹4,189.00 incl. GST.",
            "About Rs 1,900 more than usual.",
            "It went up by INR 50." })
    void anAmountNoToolReturnedIsAValueViolation(String sentence) {
        assertThat(gate.check(sentence).verdict()).isEqualTo(Verdict.VALUE_VIOLATION);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Your bill is ₹2,094.50 excl. GST higher.",
            "Your bill is ₹2,094.50 higher.",
            "Your bill is ₹2094.50 incl. GST higher.",
            "Your bill is Rs. 2,094.50 incl. GST higher.",
            "The plan costs ₹599.00 + GST." })
    void aKnownAmountWithAnotherOrNoLabelIsALabelMismatch(String sentence) {
        assertThat(gate.check(sentence).verdict()).isEqualTo(Verdict.LABEL_MISMATCH);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "why is my bill ₹3,000?|Your bill is not ₹3,000.",
            "why is my bill ₹3,000?|Your bill is not ₹3,000.00 incl. GST.",
            "I was promised a Rs 5,000 credit|Your ₹5,000 credit is noted.",
            "they said 5000 rupees back|You will get ₹5,000 back.",
            "a refund of 750/- please|A refund of ₹750 is possible." })
    void anAmountOnlyTheCustomerTypedIsReportedSeparately(String customer, String sentence) {
        GroundingGate g = gate();
        g.customerSaid(customer);
        assertThat(g.check(sentence).verdict()).isEqualTo(Verdict.CUSTOMER_AMOUNT);
    }

    @Test
    void aCustomerFigureThatAToolReturnedIsJudgedLikeAnyToolAmount() {
        GroundingGate g = gate();
        g.customerSaid("is ₹2,094.50 right?");
        assertThat(g.check("Yes, the increase is ₹2,094.50 incl. GST.").verdict()).isEqualTo(Verdict.PASS);
        assertThat(g.check("Yes, the increase is ₹2,094.50.").verdict()).isEqualTo(Verdict.LABEL_MISMATCH);
        assertThat(g.check("It is not ₹3,000.").verdict()).isEqualTo(Verdict.VALUE_VIOLATION);
    }

    @Test
    void theCanaryIsAPromptLeak() {
        assertThat(gate.check("My reference is bsa-ref-test.").verdict()).isEqualTo(Verdict.PROMPT_LEAK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I have cancelled the Astro Daily subscription.",
            "I've credited your account.",
            "We have refunded the charge.",
            "I just switched your plan.",
            "The amount has been credited to your account." })
    void claimsOfAnActionAreRewritten(String sentence) {
        GroundingGate.Result r = gate.check(sentence);
        assertThat(r.verdict()).isEqualTo(Verdict.PASS);
        assertThat(r.actionClaimRewritten()).isTrue();
        assertThat(r.text()).isEqualTo(GroundingGate.ACTION_CLAIM_REPLACEMENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Your plan changed on 1 September.",
            "I can cancel it for you once you confirm.",
            "I checked your usage.",
            "If you like, I'll raise a dispute after you confirm." })
    void statementsThatClaimNoActionOfOursAreLeftAlone(String sentence) {
        GroundingGate.Result r = gate.check(sentence);
        assertThat(r.actionClaimRewritten()).isFalse();
        assertThat(r.text()).isEqualTo(sentence);
    }

    /** actions.md §12: a claim passes only for effects the BSS confirmed (execution receipts). */
    @Test
    void aPartiallyExecutedVasAllowsTheUnsubscribeClaimAndBlocksAnyRefundClaim() {
        GroundingGate g = gate();
        g.effectsDone(Set.of("UNSUBSCRIBE"));

        assertThat(g.check("Astro Daily has been cancelled.").actionClaimRewritten()).isFalse();
        assertThat(g.check("I have unsubscribed you from Astro Daily.").actionClaimRewritten()).isFalse();
        assertThat(g.check("Astro Daily has been cancelled; the refund will be handled manually by our billing team.")
            .actionClaimRewritten()).isFalse();
        for (String refundClaim : List.of("Your refund has been processed.", "We have refunded the charges.",
                "Astro Daily has been cancelled and the refund has been processed.")) {
            GroundingGate.Result r = g.check(refundClaim);
            assertThat(r.actionClaimRewritten()).as(refundClaim).isTrue();
            assertThat(r.text()).isEqualTo(GroundingGate.PARTIAL_CLAIM_REPLACEMENT);
        }
    }

    @Test
    void claimsPassOnlyForEffectsThatAreDone() {
        GroundingGate g = gate();
        g.effectsDone(Set.of("UNSUBSCRIBE", "REFUND", "DISPUTE"));

        assertThat(g.check("Astro Daily has been cancelled and refunded.").actionClaimRewritten()).isFalse();
        assertThat(g.check("I have raised the dispute.").actionClaimRewritten()).isFalse();
        assertThat(g.check("I have credited your account.").actionClaimRewritten()).isTrue();
        assertThat(g.check("I have switched your plan.").actionClaimRewritten()).isTrue();
        // A generic verb naming no known effect is never accepted.
        assertThat(g.check("I have processed everything.").actionClaimRewritten()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Your bill has been issued on 1 September.",
            "The new rental has been applied from 1 September.",
            "Your plan changed on 1 September, so this bill has two part-month rentals." })
    void billExplanationsAreNotClaims(String sentence) {
        assertThat(gate.check(sentence).actionClaimRewritten()).isFalse();
    }

    /** A-110: a goodwill amount must be an excl. GST amount from the tool results. */
    @Test
    void onlyExclGstAmountsFromToolResultsGroundAGoodwillArgument() {
        assertThat(gate.groundedExclGst(new BigDecimal("1775.00"))).isTrue();
        assertThat(gate.groundedExclGst(new BigDecimal("1775"))).isTrue();
        assertThat(gate.groundedExclGst(new BigDecimal("2094.50"))).isFalse(); // only allowed incl. GST
        assertThat(gate.groundedExclGst(new BigDecimal("319.50"))).isFalse();  // a GST component
        assertThat(gate.groundedExclGst(new BigDecimal("500.00"))).isFalse();  // never in a tool result
    }

    @Test
    void amountStringsAreListedOnceInOrder() {
        assertThat(GroundingGate.amountStrings("a ₹1.00 GST b ₹599 + GST c ₹1.00 GST"))
            .containsExactly("₹1.00 GST", "₹599 + GST");
    }
}
