package com.telco.billshock.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Free-text scrubbing before storage and the LLM (security.md §6.2; 5a answer Q-F). */
class PiiScrubberTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "call me on 9876543210, please|call me on [PHONE], please",
            "my number is +91 98765 43210.|my number is [PHONE].",
            "or 09876543210|or [PHONE]",
            "the seed number +915550001001 too|the seed number [PHONE] too",
            "mail me at priya.s+bills@example.co.in now|mail me at [EMAIL] now",
            "aadhaar 2345 6789 0123|aadhaar [ID]",
            "card 4111 1111 1111 1111 expired|card [CARD] expired",
            "card 4111-1111-1111-1111|card [CARD]",
            "IFSC HDFC0001234 please|IFSC [BANK] please",
            "account no. 12345678901 at the bank|account [BANK] at the bank" })
    void replacesIdentifiers(String input, String expected) {
        assertThat(PiiScrubber.scrub(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Why is my bill ₹2,919.32 this month?",
            "It was ₹1,23,450.00 incl. GST",
            "Line 1001260901 looks wrong",          // a line-item id, not a mobile number (starts with 1)
            "Bill 10012609 for 2026-09",
            "I used 58 GB, not 43",
            "Pack IR_GCC_7D costs 499 + GST" })
    void leavesAmountsIdsAndOrdinaryNumbersAlone(String input) {
        assertThat(PiiScrubber.scrub(input)).isEqualTo(input);
    }
}
