package com.telco.billshock.analysis;

import com.telco.billshock.analysis.BillDiff.Finding;
import com.telco.billshock.analysis.BillDiff.FindingType;
import com.telco.billshock.analysis.PlanSimulation.PlanOption;
import com.telco.billshock.analysis.PlanSimulation.Status;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.Money;
import com.telco.billshock.support.IntegrationTest;
import com.telco.billshock.support.SeedExpectations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * The engines on the real seed, through the JPA/JDBC read models and the mock TMF622:
 * the same expectations as the unit tests (seed-scenarios.md §5.7, §7; deterministic-core.md §3.6).
 */
@IntegrationTest
class AnalysisSeedIT {

    private static final BillPeriod SEPTEMBER = BillPeriod.of(2026, 9);

    @Autowired
    BillDiffEngine diffEngine;

    @Autowired
    PlanSimulator simulator;

    static Stream<SeedExpectations.Chat> chatColumn() {
        return SeedExpectations.chatColumn();
    }

    static Stream<SeedExpectations.CauseRow> causes() {
        return SeedExpectations.causes();
    }

    @ParameterizedTest
    @MethodSource("chatColumn")
    void chatColumn(SeedExpectations.Chat expected) {
        BillDiff diff = diffEngine.diff(AccountId.of(expected.account()), SEPTEMBER).orElseThrow();

        assertThat(diff.currentTotal()).isEqualTo(Money.of(expected.currentTotal()));
        assertThat(diff.baselineTotal()).isEqualTo(Money.of(expected.baselineTotal()));
        assertThat(diff.totalExcess()).isEqualTo(Money.of(expected.totalExcess()));
        assertThat(diff.excessPercent()).isEqualTo(new BigDecimal(expected.excessPercent()));
        assertThat(diff.ratio()).isEqualTo(new BigDecimal(expected.ratio()));
        assertThat(diff.verdict()).isEqualTo(expected.verdict());
    }

    @ParameterizedTest
    @MethodSource("causes")
    void causes(SeedExpectations.CauseRow expected) {
        BillDiff diff = diffEngine.diff(AccountId.of(expected.account()), SEPTEMBER).orElseThrow();

        assertThat(diff.causes()).singleElement().satisfies(cause -> {
            assertThat(cause.group()).isEqualTo(expected.group());
            assertThat(cause.amountExclGst()).isEqualTo(Money.of(expected.exclGst()));
            assertThat(cause.gstAmount()).isEqualTo(Money.of(expected.gst()));
            assertThat(cause.amountInclGst()).isEqualTo(Money.of(expected.inclGst()));
        });
    }

    @Test
    void findings() {
        assertThat(diffEngine.diff(AccountId.of(1005), SEPTEMBER).orElseThrow().findings())
            .extracting(Finding::type, Finding::lineItemIds)
            .containsExactly(tuple(FindingType.DUPLICATE_CHARGE, List.of(1005260901L, 1005260902L)));
        assertThat(diffEngine.diff(AccountId.of(1003), SEPTEMBER).orElseThrow().findings())
            .extracting(Finding::type, Finding::subscriptionId)
            .containsExactly(tuple(FindingType.NEW_SUBSCRIPTION_CHARGE, "SUB-1003-VAS-ASTRO"));
        assertThat(diffEngine.diff(AccountId.of(1004), SEPTEMBER).orElseThrow().findings())
            .extracting(Finding::type)
            .containsExactly(FindingType.PLAN_CHANGE_PRORATION);
        assertThat(diffEngine.diff(AccountId.of(1006), SEPTEMBER).orElseThrow().findings()).isEmpty();
        assertThat(diffEngine.diff(AccountId.of(1001), SEPTEMBER).orElseThrow().findings()).isEmpty();
    }

    @Test
    void dataOverageSimulation() {
        PlanSimulation sim = simulator.simulate(AccountId.of(1002), SEPTEMBER).orElseThrow();

        assertThat(sim.current().costExclGst()).isEqualTo(Money.of("767.64"));
        assertThat(sim.plans()).extracting(PlanOption::planCode, PlanOption::savingExclGst, PlanOption::totalInclGst)
            .containsExactly(tuple("PP_499", Money.of("268.64"), Money.of("588.82")),
                    tuple("PP_599", Money.of("168.64"), Money.of("706.82")),
                    tuple("PP_699", Money.of("68.64"), Money.of("824.82")));
        assertThat(sim.bestAddOn().addOnCode()).isEqualTo("DATA_10GB");
        assertThat(sim.bestAddOn().totalInclGst()).isEqualTo(Money.of("841.15"));
    }

    @Test
    void roamingSimulation() {
        PlanSimulation sim = simulator.simulate(AccountId.of(1001), SEPTEMBER).orElseThrow();

        assertThat(sim.plans()).isEmpty();
        assertThat(sim.bestAddOn().addOnCode()).isEqualTo("IR_GCC_7D");
        assertThat(sim.bestAddOn().savingExclGst()).isEqualTo(Money.of("876.00"));
        assertThat(sim.bestAddOn().savingInclGst()).isEqualTo(Money.of("1033.68"));
    }

    @Test
    void planChangeSimulation() {
        PlanSimulation sim = simulator.simulate(AccountId.of(1004), SEPTEMBER).orElseThrow();

        assertThat(sim.status()).isEqualTo(Status.RECENT_PLAN_CHANGE);
        assertThat(sim.recentPlanChange().effectiveDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(sim.plans()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({ "1003, 499.00", "1005, 599.00", "1006, 523.00" })
    void noSaving(long account, String currentCost) {
        PlanSimulation sim = simulator.simulate(AccountId.of(account), SEPTEMBER).orElseThrow();

        assertThat(sim.status()).isEqualTo(Status.SIMULATED);
        assertThat(sim.current().costExclGst()).isEqualTo(Money.of(currentCost));
        assertThat(sim.plans()).isEmpty();
        assertThat(sim.bestAddOn()).isNull();
    }
}
