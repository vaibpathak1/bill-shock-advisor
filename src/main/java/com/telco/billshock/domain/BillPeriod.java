package com.telco.billshock.domain;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * A billing month, stored as its first day: the month of the bill date
 * (data-architecture.md §3). Used for {@code bill_period}, {@code billed_period} and
 * {@code usage_period}.
 */
public record BillPeriod(LocalDate firstDay) implements Comparable<BillPeriod> {

    public BillPeriod {
        if (firstDay == null || firstDay.getDayOfMonth() != 1) {
            throw new IllegalArgumentException("A bill period is the first day of a month: " + firstDay);
        }
    }

    public static BillPeriod of(YearMonth month) {
        return new BillPeriod(month.atDay(1));
    }

    public static BillPeriod of(int year, int month) {
        return of(YearMonth.of(year, month));
    }

    public BillPeriod minusMonths(long months) {
        return new BillPeriod(firstDay.minusMonths(months));
    }

    public BillPeriod plusMonths(long months) {
        return new BillPeriod(firstDay.plusMonths(months));
    }

    @Override
    public int compareTo(BillPeriod other) {
        return firstDay.compareTo(other.firstDay);
    }

    @Override
    public String toString() {
        return YearMonth.from(firstDay).toString();
    }
}
