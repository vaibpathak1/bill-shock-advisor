package com.telco.billshock.bss.internal.readmodel;

import org.springframework.data.repository.Repository;

import java.time.LocalDate;
import java.util.List;

interface LineItemRepository extends Repository<LineItemEntity, LineItemKey> {

    /** Q2: one partition, index (bill_id, category). */
    List<LineItemEntity> findByBillPeriodAndBillIdOrderByLineItemId(LocalDate billPeriod, long billId);
}
