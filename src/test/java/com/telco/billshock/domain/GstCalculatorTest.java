package com.telco.billshock.domain;

import com.telco.billshock.domain.GstCalculator.GstBreakdown;
import com.telco.billshock.domain.GstCalculator.TaxComponent;
import com.telco.billshock.domain.GstCalculator.TaxLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The worked examples of seed-scenarios.md §2 (A-72). */
class GstCalculatorTest {

    private final GstCalculator gst = new GstCalculator(new BigDecimal("18.00"));

    @Test
    void intraStateSplitsIntoCgstAndSgstRoundedSeparately() {
        // 528.50 x 9% = 47.5650: a tie, HALF_EVEN keeps 47.56 (HALF_UP would give 47.57).
        GstBreakdown breakdown = gst.compute(Money.of("528.50"), SupplyType.INTRA);

        assertThat(breakdown.lines()).extracting(TaxLine::component)
            .containsExactly(TaxComponent.CGST, TaxComponent.SGST);
        assertThat(breakdown.lines()).extracting(TaxLine::amount)
            .containsExactly(Money.of("47.56"), Money.of("47.56"));
        assertThat(breakdown.lines()).extracting(TaxLine::ratePercent)
            .allSatisfy(rate -> assertThat(rate).isEqualByComparingTo("9.00"));
        assertThat(breakdown.total()).isEqualTo(Money.of("95.12"));
    }

    @Test
    void interStateIsOneIgstLine() {
        GstBreakdown breakdown = gst.compute(Money.of("767.64"), SupplyType.INTER);

        assertThat(breakdown.lines()).singleElement().satisfies(line -> {
            assertThat(line.component()).isEqualTo(TaxComponent.IGST);
            assertThat(line.ratePercent()).isEqualByComparingTo("18.00");
            assertThat(line.amount()).isEqualTo(Money.of("138.18"));
        });
    }

    @Test
    void twoRoundedHalvesCanDifferFromOneRoundedWhole() {
        Money taxable = Money.of("724.50");

        assertThat(gst.compute(taxable, SupplyType.INTRA).total()).isEqualTo(Money.of("130.40"));
        assertThat(gst.compute(taxable, SupplyType.INTER).total()).isEqualTo(Money.of("130.41"));
    }

    @ParameterizedTest
    @CsvSource({
            "699.00,  INTRA, 125.82",
            "2474.00, INTRA, 445.32",
            "1198.00, INTRA, 215.64",
            "399.00,  INTER, 71.82",
            "544.17,  INTER, 97.95",
            "196.00,  INTRA, 35.28",
            "1775.00, INTRA, 319.50"
    })
    void matchesSeedScenarioTaxTotals(String taxable, SupplyType supplyType, String expectedTax) {
        assertThat(gst.compute(Money.of(taxable), supplyType).total()).isEqualTo(Money.of(expectedTax));
    }

    @Test
    void supplyTypeFollowsPlaceOfSupply() {
        // Seed configuration (A-73): registered in Maharashtra only.
        assertThat(SupplyType.of("27", Set.of("27"))).isEqualTo(SupplyType.INTRA);
        assertThat(SupplyType.of("29", Set.of("27"))).isEqualTo(SupplyType.INTER);
        // An operator registered in every state it serves bills intra-state everywhere.
        assertThat(SupplyType.of("29", Set.of("27", "29", "07", "33"))).isEqualTo(SupplyType.INTRA);
    }
}
