package com.telco.billshock.bss.internal.readmodel;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** Primary key of {@code usage_period}: {@code (account_id, billed_period, usage_period)}. */
class UsagePeriodKey implements Serializable {

    private long accountId;
    private LocalDate billedPeriod;
    private LocalDate usagePeriod;

    protected UsagePeriodKey() {
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UsagePeriodKey other && accountId == other.accountId
                && Objects.equals(billedPeriod, other.billedPeriod) && Objects.equals(usagePeriod, other.usagePeriod);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, billedPeriod, usagePeriod);
    }
}
