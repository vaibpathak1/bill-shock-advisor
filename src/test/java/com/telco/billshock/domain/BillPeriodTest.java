package com.telco.billshock.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BillPeriodTest {

    @Test
    void isTheFirstDayOfTheMonth() {
        assertThat(BillPeriod.of(2026, 9).firstDay()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new BillPeriod(LocalDate.of(2026, 9, 16)));
    }

    @Test
    void movesByMonthsAndOrders() {
        BillPeriod september = BillPeriod.of(2026, 9);

        assertThat(september.minusMonths(6)).isEqualTo(BillPeriod.of(2026, 3));
        assertThat(september.minusMonths(1)).isLessThan(september);
        assertThat(september).hasToString("2026-09");
    }
}
