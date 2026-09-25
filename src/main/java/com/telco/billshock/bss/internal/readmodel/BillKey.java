package com.telco.billshock.bss.internal.readmodel;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** Primary key of the partitioned {@code bill} table: {@code (bill_period, bill_id)}. */
class BillKey implements Serializable {

    private LocalDate billPeriod;
    private long billId;

    protected BillKey() {
    }

    BillKey(LocalDate billPeriod, long billId) {
        this.billPeriod = billPeriod;
        this.billId = billId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BillKey other && billId == other.billId && Objects.equals(billPeriod, other.billPeriod);
    }

    @Override
    public int hashCode() {
        return Objects.hash(billPeriod, billId);
    }
}
