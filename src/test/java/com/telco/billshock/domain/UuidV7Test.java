package com.telco.billshock.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** UUIDv7 carries the partition month of chat memory (llm-architecture.md §8). */
class UuidV7Test {

    @Test
    void isVersion7WithTheRfcVariantAndTheTimestamp() {
        Instant at = Instant.parse("2026-09-25T18:30:00.123Z");
        UUID id = UuidV7.at(at);

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(UuidV7.timestamp(id)).isEqualTo(at);
    }

    @Test
    void theMonthIsTheUtcMonthOfTheTimestamp() {
        assertThat(UuidV7.month(UuidV7.at(Instant.parse("2026-09-30T23:59:59Z")))).isEqualTo(LocalDate.of(2026, 9, 1));
        // 1 Oct 04:00 IST is still 30 Sep UTC: the partition key is UTC, as in V6.
        assertThat(UuidV7.month(UuidV7.at(Instant.parse("2026-09-30T22:30:00Z")))).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(UuidV7.month(UuidV7.at(Instant.parse("2026-10-01T00:00:00Z")))).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void idsAreUniqueAndOrderedByTime() {
        UUID a = UuidV7.at(Instant.parse("2026-09-25T00:00:00Z"));
        UUID b = UuidV7.at(Instant.parse("2026-09-25T00:00:01Z"));
        assertThat(a).isNotEqualTo(UuidV7.at(Instant.parse("2026-09-25T00:00:00Z")));
        assertThat(Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits())).isNegative();
    }

    @Test
    void rejectsOtherVersions() {
        assertThatThrownBy(() -> UuidV7.month(UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    }
}
