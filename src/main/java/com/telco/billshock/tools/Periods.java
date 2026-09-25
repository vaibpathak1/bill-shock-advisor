package com.telco.billshock.tools;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import com.telco.billshock.bss.BillingReadModel;
import com.telco.billshock.bss.BillingReadModel.BillSummary;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;

/** Resolves the optional {@code YYYY-MM} period argument of a tool to one of the caller's bills. */
final class Periods {

    private Periods() {
    }

    /** @throws IllegalArgumentException if the text is not {@code YYYY-MM} */
    static Optional<BillPeriod> parse(String period) {
        if (period == null || period.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(BillPeriod.of(YearMonth.parse(period.strip())));
        }
        catch (DateTimeParseException e) {
            throw new IllegalArgumentException("billPeriod must look like 2026-09");
        }
    }

    /** The bill of the period, or the latest bill when no period is given. */
    static Optional<BillSummary> bill(BillingReadModel billing, AccountId account, Optional<BillPeriod> period) {
        return period.isPresent() ? billing.bill(account, period.get()) : billing.latestBill(account);
    }
}
