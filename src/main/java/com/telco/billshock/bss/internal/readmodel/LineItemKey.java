package com.telco.billshock.bss.internal.readmodel;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** Primary key of the partitioned {@code bill_line_item} table: {@code (bill_period, line_item_id)}. */
class LineItemKey implements Serializable {

    private LocalDate billPeriod;
    private long lineItemId;

    protected LineItemKey() {
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LineItemKey other && lineItemId == other.lineItemId
                && Objects.equals(billPeriod, other.billPeriod);
    }

    @Override
    public int hashCode() {
        return Objects.hash(billPeriod, lineItemId);
    }
}
