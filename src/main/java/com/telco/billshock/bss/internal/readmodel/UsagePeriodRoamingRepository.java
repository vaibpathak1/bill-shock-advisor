package com.telco.billshock.bss.internal.readmodel;

import org.springframework.data.repository.Repository;

import java.time.LocalDate;
import java.util.List;

interface UsagePeriodRoamingRepository extends Repository<UsagePeriodRoamingEntity, UsagePeriodRoamingKey> {

    /** Primary-key prefix (account_id, billed_period), one partition. */
    List<UsagePeriodRoamingEntity> findByAccountIdAndBilledPeriodOrderByUsagePeriodAscCountryCodeAsc(long accountId,
            LocalDate billedPeriod);
}
