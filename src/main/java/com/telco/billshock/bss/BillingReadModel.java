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

    /**
     * The account's most recent bill: the "current" bill, independent of today's date
     * (seed-scenarios.md §1).
     */
    Optional<BillSummary> latestBill(AccountId accountId);

    /** All line items of one bill, TAX lines included, in line order. */
    List<LineItem> lineItems(AccountId accountId, BillPeriod billPeriod);

    /** Usage aggregates billed on one bill, one row per usage period (query Q3). */
    List<UsagePeriodSummary> usage(AccountId accountId, BillPeriod billedPeriod);

    /** Roaming usage by country billed on one bill ({@code usage_period_roaming}, ADR-007). */
    List<RoamingUsage> roamingUsage(AccountId accountId, BillPeriod billedPeriod);

    /** The account's reference data. */
    Optional<AccountSummary> account(AccountId accountId);

    /** @param msisdn PII: mask it before it leaves the server (security.md §6) */
    record AccountSummary(AccountId accountId, String msisdn, int billCycleDay, String gstStateCode, String status) {
    }

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

    /**
     * @param voiceMin domestic minutes only
     * @param isdMin international (ISD) minutes (Q-28)
     * @param voiceCharge all VOICE line items of the period: domestic and ISD
     */
    record UsagePeriodSummary(AccountId accountId, BillPeriod billedPeriod, BillPeriod usagePeriod,
            BigDecimal dataMb, Money dataCharge, BigDecimal voiceMin, BigDecimal isdMin, Money voiceCharge,
            int smsCount, Money smsCharge, BigDecimal roamingDataMb, BigDecimal roamingVoiceMin,
            int roamingSmsCount, Money roamingCharge) {
    }

    /**
     * @param countryCode ISO 3166-1 alpha-2
     * @param firstDay first day with roaming usage in the country
     * @param lastDay last day with roaming usage in the country
     */
    record RoamingUsage(BillPeriod billedPeriod, BillPeriod usagePeriod, String countryCode, LocalDate firstDay,
            LocalDate lastDay, BigDecimal dataMb, BigDecimal voiceMin, int smsCount, Money charge) {
    }
}
