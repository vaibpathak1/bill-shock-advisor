package com.telco.billshock.bss.internal.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Read-only mapping of {@code bill_line_item}. */
@Entity
@Immutable
@Table(name = "bill_line_item")
@IdClass(LineItemKey.class)
class LineItemEntity {

    @Id
    @Column(name = "bill_period")
    private LocalDate billPeriod;

    @Id
    @Column(name = "line_item_id")
    private long lineItemId;

    @Column(name = "bill_id")
    private long billId;

    @Column(name = "account_id")
    private long accountId;

    private String category;

    private String description;

    @Column(name = "usage_period")
    private LocalDate usagePeriod;

    @Column(name = "service_period_start")
    private LocalDate servicePeriodStart;

    @Column(name = "service_period_end")
    private LocalDate servicePeriodEnd;

    @Column(name = "subscription_id")
    private String subscriptionId;

    private BigDecimal quantity;

    private String unit;

    private BigDecimal amount;

    @Column(name = "tax_component")
    private String taxComponent;

    @Column(name = "tax_rate")
    private BigDecimal taxRate;

    @Column(name = "external_ref")
    private String externalRef;

    protected LineItemEntity() {
    }

    LocalDate getBillPeriod() {
        return billPeriod;
    }

    long getLineItemId() {
        return lineItemId;
    }

    long getBillId() {
        return billId;
    }

    long getAccountId() {
        return accountId;
    }

    String getCategory() {
        return category;
    }

    String getDescription() {
        return description;
    }

    LocalDate getUsagePeriod() {
        return usagePeriod;
    }

    LocalDate getServicePeriodStart() {
        return servicePeriodStart;
    }

    LocalDate getServicePeriodEnd() {
        return servicePeriodEnd;
    }

    String getSubscriptionId() {
        return subscriptionId;
    }

    BigDecimal getQuantity() {
        return quantity;
    }

    String getUnit() {
        return unit;
    }

    BigDecimal getAmount() {
        return amount;
    }

    String getTaxComponent() {
        return taxComponent;
    }

    BigDecimal getTaxRate() {
        return taxRate;
    }

    String getExternalRef() {
        return externalRef;
    }
}
