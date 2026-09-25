package com.telco.billshock.bss;

import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.Money;
import com.telco.billshock.domain.SupplyType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Read access to the local replicas of bills, line items and bill-period usage
 * aggregates (data-architecture.md §4.1, §4.3; ADR-007). Line items are authoritative for
 * money; usage aggregates explain quantities.
 */
public interface BillingReadModel {

    /** Bills of one account in {@code [from, to]}, oldest first (query Q1). */
    List<BillSummary> billHistory(AccountId accountId, BillPeriod from, BillPeriod to);

    Optional<BillSummary> bill(AccountId accountId, BillPeriod billPeriod);

    /** All line items of one bill, TAX lines included, in line order. */
    List<LineItem> lineItems(AccountId accountId, BillPeriod billPeriod);

    /** Usage aggregates billed on one bill, one row per usage period (query Q3). */
    List<UsagePeriodSummary> usage(AccountId accountId, BillPeriod billedPeriod);

    record BillSummary(long billId, AccountId accountId, BillPeriod billPeriod,
            LocalDate periodStart, LocalDate periodEnd, LocalDate billDate,
            String placeOfSupply, SupplyType supplyType,
            Money subtotal, Money taxTotal, Money total) {
    }

    /**
     * @param description untrusted text from BSS (security.md §5)
     * @param amount before GST; on TAX lines, the tax itself
     * @param taxComponent CGST, SGST or IGST on TAX lines, otherwise {@code null}
     */
    record LineItem(long lineItemId, long billId, BillPeriod billPeriod, String category,
            String description, BillPeriod usagePeriod, LocalDate servicePeriodStart,
            LocalDate servicePeriodEnd, String subscriptionId, BigDecimal quantity, String unit,
            Money amount, String taxComponent, BigDecimal taxRate, String externalRef) {
    }

    record UsagePeriodSummary(AccountId accountId, BillPeriod billedPeriod, BillPeriod usagePeriod,
            BigDecimal dataMb, Money dataCharge, BigDecimal voiceMin, Money voiceCharge,
            int smsCount, Money smsCharge, BigDecimal roamingDataMb, BigDecimal roamingVoiceMin,
            int roamingSmsCount, Money roamingCharge) {
    }
}
