package com.telco.billshock.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MoneyTest {

    @Test
    void keepsExactlyTwoDecimals() {
        assertThat(Money.of("599").amount()).isEqualByComparingTo("599.00");
        assertThat(Money.of("599").amount().scale()).isEqualTo(2);
    }

    @Test
    void rejectsImplicitRounding() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of("47.565"));
    }

    @Test
    void roundsHalfEvenOnRequest() {
        assertThat(Money.rounded(new BigDecimal("47.565"))).isEqualTo(Money.of("47.56"));
        assertThat(Money.rounded(new BigDecimal("47.575"))).isEqualTo(Money.of("47.58"));
        assertThat(Money.rounded(new BigDecimal("138.1752"))).isEqualTo(Money.of("138.18"));
    }

    @Test
    void multipliesAndRoundsHalfEven() {
        // Proration of account 1004 (seed-scenarios.md §5.4): 399 x 16/31 and 699 x 15/31.
        assertThat(Money.of("399.00").times(new BigDecimal(16)).amount()).isEqualByComparingTo("6384.00");
        assertThat(Money.rounded(new BigDecimal("6384").divide(new BigDecimal(31), 10, Money.ROUNDING)))
            .isEqualTo(Money.of("205.94"));
        assertThat(Money.rounded(new BigDecimal("10485").divide(new BigDecimal(31), 10, Money.ROUNDING)))
            .isEqualTo(Money.of("338.23"));
        assertThat(Money.of("18432").times(new BigDecimal("0.02"))).isEqualTo(Money.of("368.64"));
    }

    @Test
    void addsAndSubtracts() {
        assertThat(Money.of("2474.00").plus(Money.of("445.32"))).isEqualTo(Money.of("2919.32"));
        assertThat(Money.of("2919.32").minus(Money.of("824.82"))).isEqualTo(Money.of("2094.50"));
        assertThat(Money.of("1.00").minus(Money.of("2.00")).isNegative()).isTrue();
    }

    @Test
    void averagesWithOneHalfEvenRounding() {
        // seed-scenarios.md §5.2: (470.82 + 470.82 + 543.32) / 3 = 494.9866... -> 494.99
        assertThat(Money.average(List.of(Money.of("470.82"), Money.of("470.82"), Money.of("543.32"))))
            .isEqualTo(Money.of("494.99"));
        // A tie: 0.01 / 2 = 0.005 -> 0.00 (HALF_EVEN), not 0.01.
        assertThat(Money.average(List.of(Money.of("0.00"), Money.of("0.01")))).isEqualTo(Money.of("0.00"));
        assertThatIllegalArgumentException().isThrownBy(() -> Money.average(List.of()));
    }
}
