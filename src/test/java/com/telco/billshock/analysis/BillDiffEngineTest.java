package com.telco.billshock.analysis;

import com.telco.billshock.analysis.BillDiff.Cause;
import com.telco.billshock.analysis.BillDiff.Finding;
import com.telco.billshock.analysis.BillDiff.FindingType;
import com.telco.billshock.analysis.BillDiff.GroupDelta;
import com.telco.billshock.analysis.BillDiff.Verdict;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.Money;
import com.telco.billshock.domain.SupplyType;
import com.telco.billshock.support.SeedExpectations;
import com.telco.billshock.support.SeedScenarioFixtures;
import com.telco.billshock.support.SeedScenarioFixtures.Charge;
import com.telco.billshock.support.SeedScenarioFixtures.InMemoryBilling;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static com.telco.billshock.support.SeedScenarioFixtures.SEPTEMBER;
import static org.assertj.core.api.Assertions.assertThat;

/** deterministic-core.md §2 against seed-scenarios.md §5.7 (chat column) and §7. */
class BillDiffEngineTest {

    private final InMemoryBilling billing = SeedScenarioFixtures.billing();

    private final BillDiffEngine engine = engine("100.00", "10");

    static Stream<SeedExpectations.Chat> chatColumn() {
        return SeedExpectations.chatColumn();
    }

    static Stream<SeedExpectations.CauseRow> causes() {
        return SeedExpectations.causes();
    }

    @ParameterizedTest
    @MethodSource("chatColumn")
    void matchesTheChatColumnOfTheExpectationsTable(SeedExpectations.Chat expected) {
        BillDiff diff = engine.diff(AccountId.of(expected.account()), SEPTEMBER).orElseThrow();

        assertThat(diff.currentTotal()).isEqualTo(Money.of(expected.currentTotal()));
        assertThat(diff.baselineBillCount()).isEqualTo(3);
        assertThat(diff.baselineTotal()).isEqualTo(Money.of(expected.baselineTotal()));
        assertThat(diff.totalExcess()).isEqualTo(Money.of(expected.totalExcess()));
        assertThat(diff.excessPercent()).isEqualTo(new BigDecimal(expected.excessPercent()));
        assertThat(diff.ratio()).isEqualTo(new BigDecimal(expected.ratio()));
        assertThat(diff.verdict()).isEqualTo(expected.verdict());
    }

    @ParameterizedTest
    @MethodSource("causes")
    void explainsEachFlaggedBillWithOneCauseThatAddsUpExactly(SeedExpectations.CauseRow expected) {
        BillDiff diff = engine.diff(AccountId.of(expected.account()), SEPTEMBER).orElseThrow();

        assertThat(diff.causes()).singleElement().satisfies(cause -> {
            assertThat(cause.group()).isEqualTo(expected.group());
            assertThat(cause.amountExclGst()).isEqualTo(Money.of(expected.exclGst()));
            assertThat(cause.gstAmount()).isEqualTo(Money.of(expected.gst()));
            assertThat(cause.amountInclGst()).isEqualTo(Money.of(expected.inclGst()));
        });
        assertThat(diff.roundingAdjustmentExclGst()).isEqualTo(Money.ZERO);
        assertThat(diff.roundingAdjustmentGst()).isEqualTo(Money.ZERO);
        assertThat(diff.causes().getFirst().amountInclGst()).isEqualTo(diff.totalExcess());
    }

    @Test
    void aNormalBillHasNoCausesButKeepsItsGroupDeltas() {
        BillDiff diff = engine.diff(AccountId.of(1006), SEPTEMBER).orElseThrow();

        assertThat(diff.verdict()).isEqualTo(Verdict.NORMAL);
        assertThat(diff.causes()).isEmpty();
        assertThat(diff.findings()).isEmpty();
        // ISD: 24.00 now against the mean of 24.00 (Jun), 12.00 (Jul) and 18.00 (Aug).
        assertThat(diff.groupDeltas()).extracting(GroupDelta::group, GroupDelta::delta)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(CauseGroup.PLAN, Money.ZERO),
                    org.assertj.core.groups.Tuple.tuple(CauseGroup.VOICE, Money.of("6.00")));
    }

    @Test
    void theProrationCauseNetsTheMissingRentalAgainstBothProrationLines() {
        BillDiff diff = engine.diff(AccountId.of(1004), SEPTEMBER).orElseThrow();

        assertThat(diff.causes().getFirst().lineItemIds()).containsExactly(1004260901L, 1004260902L);
        assertThat(diff.findings()).extracting(Finding::type).containsExactly(FindingType.PLAN_CHANGE_PRORATION);
    }

    @Test
    void findsTheDuplicateRental() {
        BillDiff diff = engine.diff(AccountId.of(1005), SEPTEMBER).orElseThrow();

        assertThat(diff.findings()).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FindingType.DUPLICATE_CHARGE);
            assertThat(f.lineItemIds()).containsExactly(1005260901L, 1005260902L);
        });
    }

    @Test
    void findsTheNewVasSubscriptionButNotTheLegitimateOne() {
        BillDiff diff = engine.diff(AccountId.of(1003), SEPTEMBER).orElseThrow();

        assertThat(diff.findings()).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FindingType.NEW_SUBSCRIPTION_CHARGE);
            assertThat(f.subscriptionId()).isEqualTo("SUB-1003-VAS-ASTRO");
            assertThat(f.lineItemIds()).containsExactly(1003260903L, 1003260904L, 1003260905L, 1003260906L);
        });
        assertThat(diff.causes().getFirst().lineItemIds()).hasSize(5); // Cricket Scores + 4 x Astro Daily
    }

    @Test
    void roamingIsNotANewSubscription() {
        assertThat(engine.diff(AccountId.of(1001), SEPTEMBER).orElseThrow().findings()).isEmpty();
    }

    @Test
    void causesAbsorbRoundingSoThatTheyAddUpToTheTotalExcessExactly() {
        // Baseline means per group (10.00, 5.00) round differently from the subtotal mean (515.01).
        BillPeriod p = BillPeriod.of(2026, 5);
        billing.addBill(2001, p.minusMonths(3), 1, SupplyType.INTRA, charges("10.00", "5.00"));
        billing.addBill(2001, p.minusMonths(2), 1, SupplyType.INTRA, charges("10.00", "5.00"));
        billing.addBill(2001, p.minusMonths(1), 1, SupplyType.INTRA, charges("10.01", "5.01"));
        billing.addBill(2001, p, 1, SupplyType.INTRA, charges("300.00", "5.00"));

        BillDiff diff = engine.diff(AccountId.of(2001), p).orElseThrow();

        assertThat(diff.subtotalExcess()).isEqualTo(Money.of("289.99"));
        assertThat(diff.roundingAdjustmentExclGst()).isEqualTo(Money.of("-0.01"));
        assertThat(diff.causes()).extracting(Cause::group).containsExactly(CauseGroup.DATA);
        assertThat(diff.causes().getFirst().amountExclGst()).isEqualTo(Money.of("289.99"));
        assertThat(diff.causes().stream().map(Cause::amountInclGst).reduce(Money.ZERO, Money::plus))
            .isEqualTo(diff.totalExcess());
        assertThat(diff.causes().getFirst().amountExclGst().plus(diff.causes().getFirst().gstAmount()))
            .isEqualTo(diff.causes().getFirst().amountInclGst());
    }

    @Test
    void negativeGroupsAreOffsetsAndTheCausesStillAddUp() {
        // A downgrade (rental -200) while roaming adds +1,000.
        BillPeriod p = BillPeriod.of(2026, 5);
        for (int m = 3; m >= 1; m--) {
            billing.addBill(2002, p.minusMonths(m), 1, SupplyType.INTER, List.of(Charge.of("RENTAL", "S", "699.00")));
        }
        billing.addBill(2002, p, 1, SupplyType.INTER,
                List.of(Charge.of("RENTAL", "S", "499.00"), Charge.of("ROAMING", "S", "1000.00")));

        BillDiff diff = engine.diff(AccountId.of(2002), p).orElseThrow();

        assertThat(diff.causes()).extracting(Cause::group, Cause::amountExclGst)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(CauseGroup.ROAMING, Money.of("1000.00")),
                    org.assertj.core.groups.Tuple.tuple(CauseGroup.PLAN, Money.of("-200.00")));
        assertThat(diff.causes().stream().map(Cause::amountInclGst).reduce(Money.ZERO, Money::plus))
            .isEqualTo(diff.totalExcess())
            .isEqualTo(Money.of("944.00"));
    }

    @Test
    void theChatRuleIsInclusiveOnBothLimits() {
        // 1006: excess 7.08 on a baseline of 610.06, i.e. 1.1605...%.
        assertThat(engine("7.08", "1.16").diff(AccountId.of(1006), SEPTEMBER).orElseThrow().verdict())
            .isEqualTo(Verdict.MEANINGFUL_INCREASE);
        assertThat(engine("7.09", "1.16").diff(AccountId.of(1006), SEPTEMBER).orElseThrow().verdict())
            .isEqualTo(Verdict.NORMAL);
        assertThat(engine("7.08", "1.17").diff(AccountId.of(1006), SEPTEMBER).orElseThrow().verdict())
            .isEqualTo(Verdict.NORMAL);
    }

    @Test
    void usesTheBillsThatExistWhenTheHistoryIsShort() {
        BillDiff diff = engine.diff(AccountId.of(1002), BillPeriod.of(2026, 4)).orElseThrow();

        assertThat(diff.baselineBillCount()).isEqualTo(1);
        assertThat(diff.baselineTotal()).isEqualTo(Money.of("470.82"));
        assertThat(diff.verdict()).isEqualTo(Verdict.NORMAL);
    }

    @Test
    void theFirstBillHasInsufficientHistory() {
        BillDiff diff = engine.diff(AccountId.of(1002), BillPeriod.of(2026, 3)).orElseThrow();

        assertThat(diff.verdict()).isEqualTo(Verdict.INSUFFICIENT_HISTORY);
        assertThat(diff.baselineBillCount()).isZero();
        assertThat(diff.totalExcess()).isNull();
        assertThat(diff.causes()).isEmpty();
    }

    @Test
    void anUnknownBillHasNoDiff() {
        assertThat(engine.diff(AccountId.of(1001), BillPeriod.of(2026, 1))).isEmpty();
        assertThat(engine.diff(AccountId.of(9999), SEPTEMBER)).isEmpty();
    }

    private static List<Charge> charges(String data, String voice) {
        return List.of(Charge.of("RENTAL", "S", "500.00"), Charge.of("DATA", "S", data), Charge.of("VOICE", "S", voice));
    }

    private BillDiffEngine engine(String minExcessInr, String minExcessPct) {
        return new BillDiffEngine(billing, SeedScenarioFixtures.GST, new AnalysisProperties(
                new AnalysisProperties.Chat(new BigDecimal(minExcessInr), new BigDecimal(minExcessPct)),
                new AnalysisProperties.Simulator(3)));
    }
}
