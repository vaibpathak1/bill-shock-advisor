package com.telco.billshock.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * UUID version 7 (RFC 9562): a 48-bit Unix-millisecond timestamp followed by random bits.
 * Conversation ids are UUIDv7, so the partition month of {@code chat_messages} can be derived
 * from the id alone (llm-architecture.md §8; A-104). Java 21 has no built-in generator.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        return at(Instant.now());
    }

    /** A UUIDv7 with the given timestamp (tests and replays). */
    public static UUID at(Instant instant) {
        long millis = instant.toEpochMilli();
        long randA = RANDOM.nextInt(1 << 12);
        long msb = (millis << 16) | (0x7L << 12) | randA;
        long randB = RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL;
        long lsb = 0x8000_0000_0000_0000L | randB;
        return new UUID(msb, lsb);
    }

    /** The embedded timestamp; rejects any UUID that is not version 7. */
    public static Instant timestamp(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("Not a UUIDv7: " + uuid);
        }
        return Instant.ofEpochMilli(uuid.getMostSignificantBits() >>> 16);
    }

    /** The first day of the UTC month of the embedded timestamp (the partition key). */
    public static LocalDate month(UUID uuid) {
        return LocalDate.ofInstant(timestamp(uuid), ZoneOffset.UTC).withDayOfMonth(1);
    }
}
