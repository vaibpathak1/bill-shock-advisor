package com.telco.billshock.bss.internal.readmodel;

import org.springframework.data.repository.Repository;

import java.time.LocalDate;
import java.util.List;

interface UsagePeriodRepository extends Repository<UsagePeriodEntity, UsagePeriodKey> {

    /** Q3: primary key (account_id, billed_period, usage_period). */
    List<UsagePeriodEntity> findByAccountIdAndBilledPeriodOrderByUsagePeriod(long accountId, LocalDate billedPeriod);
}
