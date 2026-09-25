package com.telco.billshock.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** security.md §6.1: tools only ever return the last 4 digits. */
class MsisdnMaskTest {

    @Test
    void keepsTheLastFourDigits() {
        assertThat(MsisdnMask.mask("+915550001001")).isEqualTo("******1001");
        assertThat(MsisdnMask.mask("98765 43210")).isEqualTo("******3210");
    }

    @Test
    void masksShortOrMissingNumbersCompletely() {
        assertThat(MsisdnMask.mask("12")).isEqualTo("******");
        assertThat(MsisdnMask.mask(null)).isEqualTo("******");
    }
}
