package com.telco.billshock.domain;

/**
 * Identifies a customer account. Every customer-owned row carries it; it is the
 * authorisation key and the future shard key (data-architecture.md).
 */
public record AccountId(long value) {

    public AccountId {
        if (value <= 0) {
            throw new IllegalArgumentException("AccountId must be positive: " + value);
        }
    }

    public static AccountId of(long value) {
        return new AccountId(value);
    }
}
