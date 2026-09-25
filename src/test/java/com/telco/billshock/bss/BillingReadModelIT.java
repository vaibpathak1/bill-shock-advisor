package com.telco.billshock.bss;

import com.telco.billshock.bss.BillingReadModel.BillSummary;
import com.telco.billshock.bss.BillingReadModel.LineItem;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.Money;
import com.telco.billshock.domain.SupplyType;
import com.telco.billshock.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class BillingReadModelIT {

    private static final BillPeriod SEPTEMBER = BillPeriod.of(2026, 9);

    @Autowired
    BillingReadModel readModel;

    @Test
    void billHistoryIsOldestFirstAndLimitedToTheRange() {
        List<BillSummary> history = readModel.billHistory(AccountId.of(1001), SEPTEMBER.minusMonths(3), SEPTEMBER);

        assertThat(history).extracting(BillSummary::billPeriod).containsExactly(BillPeriod.of(2026, 6),
                BillPeriod.of(2026, 7), BillPeriod.of(2026, 8), SEPTEMBER);
        assertThat(history.getLast()).satisfies(bill -> {
            assertThat(bill.billId()).isEqualTo(10012609L);
            assertThat(bill.supplyType()).isEqualTo(SupplyType.INTRA);
            assertThat(bill.subtotal()).isEqualTo(Money.of("2474.00"));
            assertThat(bill.taxTotal()).isEqualTo(Money.of("445.32"));
            assertThat(bill.total()).isEqualTo(Money.of("2919.32"));
            assertThat(bill.periodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
            assertThat(bill.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 31));
        });
    }

    @Test
    void lineItemsIncludeTaxLinesInOrder() {
        List<LineItem> items = readModel.lineItems(AccountId.of(1004), SEPTEMBER);

        assertThat(items).extracting(LineItem::category, LineItem::amount, LineItem::taxComponent)
            .containsExactly(
                    org.assertj.core.groups.Tuple.tuple("PRORATION", Money.of("205.94"), null),
                    org.assertj.core.groups.Tuple.tuple("PRORATION", Money.of("338.23"), null),
                    org.assertj.core.groups.Tuple.tuple("TAX", Money.of("97.95"), "IGST"));
        assertThat(items.getFirst().servicePeriodStart()).isEqualTo(LocalDate.of(2026, 8, 16));
        assertThat(items.getFirst().servicePeriodEnd()).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    void anUnknownBillHasNoLineItems() {
        assertThat(readModel.lineItems(AccountId.of(1001), BillPeriod.of(2026, 1))).isEmpty();
        assertThat(readModel.bill(AccountId.of(9999), SEPTEMBER)).isEmpty();
    }

    @Test
    void usageAggregatesForTheCurrentBill() {
        assertThat(readModel.usage(AccountId.of(1002), SEPTEMBER)).singleElement().satisfies(usage -> {
            assertThat(usage.usagePeriod()).isEqualTo(SEPTEMBER);
            assertThat(usage.dataMb()).isEqualByComparingTo(String.valueOf(58 * 1024));
            assertThat(usage.dataCharge()).isEqualTo(Money.of("368.64"));
            assertThat(usage.roamingCharge()).isEqualTo(Money.ZERO);
        });
        assertThat(readModel.usage(AccountId.of(1001), SEPTEMBER)).singleElement().satisfies(usage -> {
            assertThat(usage.roamingDataMb()).isEqualByComparingTo("550");
            assertThat(usage.roamingVoiceMin()).isEqualByComparingTo("10");
            assertThat(usage.roamingSmsCount()).isEqualTo(3);
            assertThat(usage.roamingCharge()).isEqualTo(Money.of("1775.00"));
        });
    }

    @Test
    void theLatestBillIsSeptember() {
        assertThat(readModel.latestBill(AccountId.of(1004))).get()
            .extracting(BillSummary::billPeriod, BillSummary::billId)
            .containsExactly(SEPTEMBER, 10042609L);
        assertThat(readModel.latestBill(AccountId.of(9999))).isEmpty();
    }

    @Test
    void roamingUsageByCountry() {
        assertThat(readModel.roamingUsage(AccountId.of(1001), SEPTEMBER)).singleElement().satisfies(r -> {
            assertThat(r.countryCode()).isEqualTo("AE");
            assertThat(r.firstDay()).isEqualTo(LocalDate.of(2026, 8, 12));
            assertThat(r.lastDay()).isEqualTo(LocalDate.of(2026, 8, 18));
            assertThat(r.dataMb()).isEqualByComparingTo("550");
            assertThat(r.voiceMin()).isEqualByComparingTo("10");
            assertThat(r.smsCount()).isEqualTo(3);
            assertThat(r.charge()).isEqualTo(Money.of("1775.00"));
        });
        assertThat(readModel.roamingUsage(AccountId.of(1001), BillPeriod.of(2026, 8))).isEmpty();
    }

    @Test
    void isdMinutesAreSeparateFromDomesticVoice() {
        assertThat(readModel.usage(AccountId.of(1006), SEPTEMBER)).singleElement().satisfies(usage -> {
            assertThat(usage.isdMin()).isEqualByComparingTo("4");
            assertThat(usage.voiceMin()).isEqualByComparingTo("586");
            assertThat(usage.voiceCharge()).isEqualTo(Money.of("24.00"));
        });
        assertThat(readModel.usage(AccountId.of(1001), SEPTEMBER).getFirst().isdMin()).isEqualByComparingTo("0");
    }
}
