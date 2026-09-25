package com.telco.billshock.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void amountStringsAreListedOnceInOrder() {
        assertThat(GroundingGate.amountStrings("a ₹1.00 GST b ₹599 + GST c ₹1.00 GST"))
            .containsExactly("₹1.00 GST", "₹599 + GST");
    }
}
