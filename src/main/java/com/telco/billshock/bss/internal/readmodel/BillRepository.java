package com.telco.billshock.bss.internal.readmodel;

import org.springframework.data.repository.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

interface BillRepository extends Repository<BillEntity, BillKey> {

    /** Q1: unique (account_id, bill_period) index, pruned to the partitions in range. */
    List<BillEntity> findByAccountIdAndBillPeriodBetweenOrderByBillPeriod(long accountId, LocalDate from, LocalDate to);

    Optional<BillEntity> findByAccountIdAndBillPeriod(long accountId, LocalDate billPeriod);

    /**
     * The latest bill. No partition pruning: a backward index scan per partition under a
     * Merge Append with LIMIT 1, which stops after the first row.
     */
    Optional<BillEntity> findFirstByAccountIdOrderByBillPeriodDesc(long accountId);
}
