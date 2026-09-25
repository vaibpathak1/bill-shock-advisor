package com.telco.billshock.bss.internal.readmodel;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Primary key of {@code usage_period_roaming}:
 * {@code (account_id, billed_period, usage_period, country_code)}.
 */
class UsagePeriodRoamingKey implements Serializable {

    private long accountId;
    private LocalDate billedPeriod;
    private LocalDate usagePeriod;
    private String countryCode;

    protected UsagePeriodRoamingKey() {
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UsagePeriodRoamingKey other && accountId == other.accountId
                && Objects.equals(billedPeriod, other.billedPeriod) && Objects.equals(usagePeriod, other.usagePeriod)
                && Objects.equals(countryCode, other.countryCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, billedPeriod, usagePeriod, countryCode);
    }
}
