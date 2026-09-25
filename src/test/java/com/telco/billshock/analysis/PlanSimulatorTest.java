package com.telco.billshock.analysis;

import com.telco.billshock.analysis.PlanSimulation.NotSimulatableReason;
import com.telco.billshock.analysis.PlanSimulation.PlanOption;
import com.telco.billshock.analysis.PlanSimulation.Status;
import com.telco.billshock.bss.BillingReadModel.RoamingUsage;
import com.telco.billshock.bss.CatalogReadModel;
import com.telco.billshock.bss.CatalogReadModel.TariffRate;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.Money;
import com.telco.billshock.domain.SupplyType;
import com.telco.billshock.support.SeedScenarioFixtures;
import com.telco.billshock.support.SeedScenarioFixtures.Charge;
import com.telco.billshock.support.SeedScenarioFixtures.InMemoryBilling;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.telco.billshock.support.SeedScenarioFixtures.SEPTEMBER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/** deterministic-core.md §3 against seed-scenarios.md §5.1, §5.2, §7. */
class PlanSimulatorTest {

    private final InMemoryBilling billing = SeedScenarioFixtures.billing();

    private PlanSimulator simulator(CatalogReadModel catalog) {
        return new PlanSimulator(billing, catalog, SeedScenarioFixtures.inventory(), SeedScenarioFixtures.GST,
                new AnalysisProperties(new AnalysisProperties.Chat(new BigDecimal("100"), BigDecimal.TEN),
                        new AnalysisProperties.Simulator(3)));
    }

    private PlanSimulation simulate(long account, BillPeriod period) {
        return simulator(SeedScenarioFixtures.catalog()).simulate(AccountId.of(account), period).orElseThrow();
    }

    @Test
    void dataOverageRanksTheTopThreePlansAndTheBestSingleAddOn() {
        PlanSimulation sim = simulate(1002, SEPTEMBER);

        assertThat(sim.status()).isEqualTo(Status.SIMULATED);
        assertThat(sim.current().planCode()).isEqualTo("PP_399");
        assertThat(sim.current().costExclGst()).isEqualTo(Money.of("767.64"));
        assertThat(sim.current().totalInclGst()).isEqualTo(Money.of("905.82"));
        // seed-scenarios.md §5.2
        assertThat(sim.plans()).extracting(PlanOption::planCode, PlanOption::costExclGst, PlanOption::savingExclGst,
                PlanOption::totalInclGst)
            .containsExactly(tuple("PP_499", Money.of("499.00"), Money.of("268.64"), Money.of("588.82")),
                    tuple("PP_599", Money.of("599.00"), Money.of("168.64"), Money.of("706.82")),
                    tuple("PP_699", Money.of("699.00"), Money.of("68.64"), Money.of("824.82")));
        assertThat(sim.plans().getFirst().savingInclGst()).isEqualTo(Money.of("317.00"));
        assertThat(sim.bestAddOn()).satisfies(a -> {
            assertThat(a.addOnCode()).isEqualTo("DATA_10GB");
            assertThat(a.costExclGst()).isEqualTo(Money.of("712.84"));
            assertThat(a.savingExclGst()).isEqualTo(Money.of("54.80"));
            assertThat(a.totalInclGst()).isEqualTo(Money.of("841.15"));
        });
    }

    @Test
    void roamingWithoutAPackRecommendsTheSevenDayGccPack() {
        PlanSimulation sim = simulate(1001, SEPTEMBER);

        assertThat(sim.current().costExclGst()).isEqualTo(Money.of("2474.00"));
        assertThat(sim.plans()).isEmpty(); // roaming costs the same on every plan (A-75)
        assertThat(sim.bestAddOn()).satisfies(a -> {
            assertThat(a.addOnCode()).isEqualTo("IR_GCC_7D");
            assertThat(a.costExclGst()).isEqualTo(Money.of("1598.00"));
            assertThat(a.savingExclGst()).isEqualTo(Money.of("876.00"));
            assertThat(a.savingInclGst()).isEqualTo(Money.of("1033.68"));
        });
    }

    @ParameterizedTest
    @CsvSource({ "1003, PP_499, 499.00", "1005, PP_599, 599.00", "1006, PP_499, 523.00" })
    void findsNoSavingWhereTheScenarioHasNone(long account, String plan, String currentCost) {
        PlanSimulation sim = simulate(account, SEPTEMBER);

        assertThat(sim.status()).isEqualTo(Status.SIMULATED);
        assertThat(sim.current().planCode()).isEqualTo(plan);
        // Re-rated current plan, not the billed total: VAS (1003) and the duplicate (1005) are excluded.
        assertThat(sim.current().costExclGst()).isEqualTo(Money.of(currentCost));
        assertThat(sim.plans()).isEmpty();
        assertThat(sim.bestAddOn()).isNull();
    }

    @Test
    void aPeriodWithAPlanChangeIsNotRanked() {
        PlanSimulation sim = simulate(1004, SEPTEMBER);

        assertThat(sim.status()).isEqualTo(Status.RECENT_PLAN_CHANGE);
        assertThat(sim.recentPlanChange()).satisfies(c -> {
            assertThat(c.effectiveDate()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(c.fromPlanCode()).isEqualTo("PP_399");
            assertThat(c.toPlanCode()).isEqualTo("PP_699");
        });
        assertThat(sim.current()).isNull();
        assertThat(sim.plans()).isEmpty();
        assertThat(sim.bestAddOn()).isNull();
    }

    @Test
    void theRuleExpiresAfterOneFullCycleOnTheNewPlan() {
        // October: 16 Sep - 15 Oct, all on PP_699, at 39 GB.
        BillPeriod october = BillPeriod.of(2026, 10);
        billing.addBill(1004, october, 16, SupplyType.INTER, List.of(Charge.of("RENTAL", "SUB-1004-PLAN", "699.00")));
        billing.addUsage(1004, october, 39 * 1024L, 0, Money.ZERO, Money.ZERO);

        PlanSimulation sim = simulate(1004, october);

        assertThat(sim.status()).isEqualTo(Status.SIMULATED);
        assertThat(sim.current().planCode()).isEqualTo("PP_699");
        assertThat(sim.plans().getFirst().planCode()).isEqualTo("PP_399");
        assertThat(sim.plans().getFirst().savingExclGst()).isEqualTo(Money.of("300.00"));
    }

    @Test
    void earlierPeriodsAreRatedOnTheOldPlan() {
        PlanSimulation sim = simulate(1004, BillPeriod.of(2026, 8));

        assertThat(sim.status()).isEqualTo(Status.SIMULATED);
        assertThat(sim.current().planCode()).isEqualTo("PP_399");
        assertThat(sim.current().costExclGst()).isEqualTo(Money.of("399.00"));
    }

    @Test
    void prorationLinesMarkAPlanChangeWhenBssHasNoOrder() {
        BillPeriod may = BillPeriod.of(2026, 5);
        billing.addBill(2003, may.minusMonths(1), 1, SupplyType.INTER, List.of(Charge.of("RENTAL", "S", "399.00")));
        billing.addBill(2003, may, 1, SupplyType.INTER, List.of(
                new Charge("PRORATION", "old", "S", BigDecimal.TEN, "DAY", Money.of("128.71"), LocalDate.of(2026, 4, 1),
                        LocalDate.of(2026, 4, 10), null),
                new Charge("PRORATION", "new", "S", BigDecimal.TEN, "DAY", Money.of("466.00"), LocalDate.of(2026, 4, 11),
                        LocalDate.of(2026, 4, 30), null)));

        PlanSimulation sim = simulate(2003, may);

        assertThat(sim.status()).isEqualTo(Status.RECENT_PLAN_CHANGE);
        assertThat(sim.recentPlanChange().effectiveDate()).isEqualTo(LocalDate.of(2026, 4, 11));
        assertThat(sim.recentPlanChange().fromPlanCode()).isNull();
    }

    @Test
    void aPackOnlyAppliesToATripThatFitsItsValidity() {
        // Same usage, but an 8-day trip: the 7-day pack no longer applies, and the 30-day pack costs more.
        billing.addBill(1001, BillPeriod.of(2026, 10), 1, SupplyType.INTRA,
                List.of(Charge.of("RENTAL", "SUB-1001-PLAN", "699.00"), Charge.of("ROAMING", "SUB-1001-PLAN", "1775.00")));
        billing.addUsage(1001, BillPeriod.of(2026, 10), 114 * 1024L, 0, Money.ZERO, Money.ZERO);
        addRoaming(1001, BillPeriod.of(2026, 10), "AE", LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 19));

        PlanSimulation sim = simulate(1001, BillPeriod.of(2026, 10));

        assertThat(sim.current().costExclGst()).isEqualTo(Money.of("2474.00"));
        assertThat(sim.bestAddOn()).isNull();
    }

    @Test
    void anUnconfiguredRoamingCountryIsNotSimulatable() {
        billing.addBill(1001, BillPeriod.of(2026, 10), 1, SupplyType.INTRA,
                List.of(Charge.of("RENTAL", "SUB-1001-PLAN", "699.00")));
        billing.addUsage(1001, BillPeriod.of(2026, 10), 1024L, 0, Money.ZERO, Money.ZERO);
        addRoaming(1001, BillPeriod.of(2026, 10), "ZZ", LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 13));

        assertThat(simulate(1001, BillPeriod.of(2026, 10)).notSimulatableReason())
            .isEqualTo(NotSimulatableReason.UNKNOWN_ROAMING_COUNTRY);
    }

    @Test
    void aBillWithoutUsageIsNotSimulatable() {
        billing.addBill(1002, BillPeriod.of(2026, 10), 6, SupplyType.INTER,
                List.of(Charge.of("RENTAL", "SUB-1002-PLAN", "399.00")));

        PlanSimulation sim = simulate(1002, BillPeriod.of(2026, 10));

        assertThat(sim.status()).isEqualTo(Status.NOT_SIMULATABLE);
        assertThat(sim.notSimulatableReason()).isEqualTo(NotSimulatableReason.NO_USAGE);
    }

    @Test
    void aDayBasedTariffOnTheCurrentPlanIsNotSimulatable() {
        CatalogReadModel base = SeedScenarioFixtures.catalog();
        CatalogReadModel dayBased = new CatalogReadModel() {

            @Override
            public List<Plan> plans(LocalDate onDate) {
                return base.plans(onDate).stream().map(p -> new Plan(p.code(), p.name(), p.monthlyRental(),
                        p.tariffs().stream().map(t -> new TariffRate(t.usageType(), t.band(), t.includedUnits(),
                                t.unitPrice(), t.unit(), t.usageType() == UsageType.DATA_MB)).toList())).toList();
            }

            @Override
            public List<AddOn> addOns(LocalDate onDate) {
                return base.addOns(onDate);
            }

            @Override
            public Optional<Band> roamingBand(String countryCode) {
                return base.roamingBand(countryCode);
            }
        };

        assertThat(simulator(dayBased).simulate(AccountId.of(1002), SEPTEMBER).orElseThrow().notSimulatableReason())
            .isEqualTo(NotSimulatableReason.DAY_BASED_TARIFF);
    }

    @Test
    void isdMinutesAreRatedAgainstEachPlansAllowance() {
        // 1006 with 150 ISD minutes: PP_999 includes 100, so it now costs less than PP_499 + 900.00 ISD.
        billing.addBill(1006, BillPeriod.of(2026, 10), 1, SupplyType.INTER,
                List.of(Charge.of("RENTAL", "SUB-1006-PLAN", "499.00"), Charge.of("VOICE", "SUB-1006-PLAN", "900.00")));
        billing.addUsage(1006, BillPeriod.of(2026, 10), 67 * 1024L, 150, Money.ZERO, Money.of("900.00"));

        PlanSimulation sim = simulate(1006, BillPeriod.of(2026, 10));

        assertThat(sim.current().costExclGst()).isEqualTo(Money.of("1399.00"));
        assertThat(sim.plans()).extracting(PlanOption::planCode, PlanOption::costExclGst)
            .containsExactly(tuple("PP_1199", Money.of("1199.00")), tuple("PP_999", Money.of("1299.00")));
    }

    @Test
    void anUnknownBillHasNoSimulation() {
        assertThat(simulator(SeedScenarioFixtures.catalog()).simulate(AccountId.of(1001), BillPeriod.of(2026, 1)))
            .isEmpty();
    }

    private void addRoaming(long account, BillPeriod period, String country, LocalDate first, LocalDate last) {
        billing.addRoaming(account, new RoamingUsage(period, period, country, first, last, new BigDecimal("550"),
                new BigDecimal("10"), 3, Money.of("1775.00")));
    }
}
