package com.telco.billshock.agent.internal;

import static com.telco.billshock.support.SeedScenarioFixtures.SEPTEMBER;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.telco.billshock.analysis.AnalysisProperties;
import com.telco.billshock.analysis.BillDiff;
import com.telco.billshock.analysis.BillDiffEngine;
import com.telco.billshock.analysis.PlanSimulation;
import com.telco.billshock.analysis.PlanSimulator;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.support.SeedScenarioFixtures;
import com.telco.billshock.support.SeedScenarioFixtures.InMemoryBilling;

/** The deterministic fallback (agent.md §8.3) for all 6 seed scenarios. */
class FallbackTemplatesTest {

    private final InMemoryBilling billing = SeedScenarioFixtures.billing();
    private final AnalysisProperties properties = new AnalysisProperties(
            new AnalysisProperties.Chat(new BigDecimal("100.00"), BigDecimal.TEN), new AnalysisProperties.Simulator(3));
    private final BillDiffEngine diffEngine = new BillDiffEngine(billing, SeedScenarioFixtures.GST, properties);
    private final PlanSimulator simulator = new PlanSimulator(billing, SeedScenarioFixtures.catalog(),
            SeedScenarioFixtures.inventory(), SeedScenarioFixtures.GST, properties);
    private final FallbackTemplates templates = new FallbackTemplates();

    private Optional<BillDiff> diff(long account) {
        return diffEngine.diff(AccountId.of(account), SEPTEMBER);
    }

    private String answer(long account) {
        Optional<PlanSimulation> simulation = simulator.simulate(AccountId.of(account), SEPTEMBER);
        return templates.answer(diff(account), simulation);
    }

    @Test
    void roaming1001() {
        assertThat(templates.summary(diff(1001))).isEqualTo("Your September 2026 bill is ₹2,094.50 incl. GST higher"
                + " than the average of your previous 3 bills, mainly because of international roaming charges of"
                + " ₹2,094.50 incl. GST.");
        assertThat(answer(1001))
            .contains("International roaming charges account for ₹2,094.50 incl. GST of the change.")
            .contains("(IR_GCC_7D) would have meant a new bill of")
            .contains("a saving of ₹1,033.68 incl. GST.");
    }

    @Test
    void dataOverage1002ShowsNewBillAndSavingSeparately() {
        assertThat(answer(1002))
            .contains("Data usage beyond your plan accounts for ₹410.83 incl. GST of the change.")
            .contains("(PP_499) would have meant a new bill of ₹588.82 incl. GST, a saving of ₹317.00 incl. GST.")
            .contains("(DATA_10GB) would have meant a new bill of");
    }

    @Test
    void vas1003() {
        assertThat(answer(1003))
            .contains("Value-added service charges")
            .contains("₹231.28 incl. GST")
            .contains("A subscription that was not on your earlier bills")
            .doesNotContain("would have meant");
    }

    @Test
    void proration1004() {
        assertThat(answer(1004))
            .contains("Plan charges account for ₹171.30 incl. GST of the change.")
            .contains("split (prorated)")
            .contains("Because your plan changed on 2026-09-01, a plan comparison is only meaningful from your next full bill.");
    }

    @Test
    void duplicate1005() {
        assertThat(answer(1005))
            .contains("Plan charges account for ₹706.82 incl. GST of the change.")
            .containsPattern("The charge on line \\d+ appears again on line \\d+ of this bill");
    }

    @Test
    void normal1006IsOnlyTheSummary() {
        assertThat(answer(1006)).isEqualTo("Your September 2026 bill of ₹617.14 incl. GST is in line with your recent bills.");
    }

    @Test
    void noBill() {
        assertThat(templates.answer(Optional.empty(), Optional.empty()))
            .isEqualTo("I could not find a bill on your account yet.");
    }

    @ParameterizedTest
    @ValueSource(longs = { 1001, 1002, 1003, 1004, 1005, 1006 })
    void everyAmountCarriesAGstLabel(long account) {
        Matcher m = GroundingGate.AMOUNT.matcher(answer(account));
        int amounts = 0;
        while (m.find()) {
            amounts++;
            assertThat(m.group("label")).as(m.group()).isNotNull();
        }
        assertThat(amounts).isPositive();
    }
}
