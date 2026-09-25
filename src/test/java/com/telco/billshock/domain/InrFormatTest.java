package com.telco.billshock.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Indian digit grouping (en-IN) and GST labels (Q-23, Q-30; deterministic-core.md §6). */
class InrFormatTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0.00         | ₹0.00",
            "5.50         | ₹5.50",
            "599.00       | ₹599.00",
            "2094.50      | ₹2,094.50",
            "12345.67     | ₹12,345.67",
            "123456.78    | ₹1,23,456.78",
            "1234567.00   | ₹12,34,567.00",
            "123456789.01 | ₹12,34,56,789.01",
            "-12.00       | -₹12.00",
            "-123456.00   | -₹1,23,456.00"
    })
    void groupsDigitsTheIndianWay(String amount, String expected) {
        assertThat(InrFormat.amount(Money.of(amount))).isEqualTo(expected);
    }

    @Test
    void labelsEveryAmountWithItsGstStatus() {
        assertThat(InrFormat.inclGst(Money.of("2832.00"))).isEqualTo("₹2,832.00 incl. GST");
        assertThat(InrFormat.exclGst(Money.of("2400.00"))).isEqualTo("₹2,400.00 excl. GST");
    }

    @Test
    void catalogPricesDropZeroPaise() {
        assertThat(InrFormat.plusGst(Money.of("599.00"))).isEqualTo("₹599 + GST");
        assertThat(InrFormat.plusGst(Money.of("1199.00"))).isEqualTo("₹1,199 + GST");
        assertThat(InrFormat.plusGst(Money.of("29.50"))).isEqualTo("₹29.50 + GST");
    }
}
