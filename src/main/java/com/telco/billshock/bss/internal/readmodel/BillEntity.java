package com.telco.billshock.bss.internal.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Read-only mapping of {@code bill} (BSS replica; written by ingest, never by the app). */
@Entity
@Immutable
@Table(name = "bill")
@IdClass(BillKey.class)
class BillEntity {

    @Id
    @Column(name = "bill_period")
    private LocalDate billPeriod;

    @Id
    @Column(name = "bill_id")
    private long billId;

    @Column(name = "account_id")
    private long accountId;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "bill_date")
    private LocalDate billDate;

    @Column(name = "place_of_supply")
    private String placeOfSupply;

    @Column(name = "supply_type")
    private String supplyType;

    private BigDecimal subtotal;

    @Column(name = "tax_total")
    private BigDecimal taxTotal;

    private BigDecimal total;

    protected BillEntity() {
    }

    LocalDate getBillPeriod() {
        return billPeriod;
    }

    long getBillId() {
        return billId;
    }

    long getAccountId() {
        return accountId;
    }

    LocalDate getPeriodStart() {
        return periodStart;
    }

    LocalDate getPeriodEnd() {
        return periodEnd;
    }

    LocalDate getBillDate() {
        return billDate;
    }

    String getPlaceOfSupply() {
        return placeOfSupply;
    }

    String getSupplyType() {
        return supplyType;
    }

    BigDecimal getSubtotal() {
        return subtotal;
    }

    BigDecimal getTaxTotal() {
        return taxTotal;
    }

    BigDecimal getTotal() {
        return total;
    }
}
