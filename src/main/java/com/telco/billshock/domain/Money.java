package com.telco.billshock.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount in Indian rupees with 2 decimal places. All money arithmetic uses
 * {@link BigDecimal} with {@link RoundingMode#HALF_EVEN} (SPEC §4.3 rule 1, ADR-003).
 * The LLM never computes amounts.
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        Objects.requireNonNull(amount, "amount");
        try {
            amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        }
        catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "Money has at most 2 decimal places; round explicitly with Money.rounded: " + amount, ex);
        }
    }

    /** Exact amount; fails if it has more than 2 decimal places. */
    public static Money of(String amount) {
        return new Money(new BigDecimal(amount));
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount);
    }

    /** Rounds an arbitrary-precision value HALF_EVEN to 2 decimal places. */
    public static Money rounded(BigDecimal value) {
        return new Money(value.setScale(SCALE, ROUNDING));
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    /** Multiplies by a rate or quantity and rounds HALF_EVEN to 2 decimal places. */
    public Money times(BigDecimal factor) {
        return rounded(amount.multiply(factor));
    }

    /** Applies a percentage (for example 9.00 for 9%) and rounds HALF_EVEN. */
    public Money percent(BigDecimal percent) {
        return rounded(amount.multiply(percent).movePointLeft(2));
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
