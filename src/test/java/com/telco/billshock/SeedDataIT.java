package com.telco.billshock;

import com.telco.billshock.bss.TaxProperties;
import com.telco.billshock.domain.GstCalculator;
import com.telco.billshock.domain.GstCalculator.TaxLine;
import com.telco.billshock.domain.Money;
import com.telco.billshock.domain.SupplyType;
import com.telco.billshock.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seed must reproduce docs/03-development/seed-scenarios.md exactly (approved
 * 2026-09-25): every bill total of §5, the GST rule of §2 and the usage consistency rules
 * of §9.
 */
@IntegrationTest
class SeedDataIT {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    GstCalculator gst;

    @Autowired
    TaxProperties taxProperties;

    @Test
    void sixAccountsWithSevenBillsEachFromMarchToSeptember2026() {
        Map<Long, List<LocalDate>> periods = jdbc.sql("SELECT account_id, bill_period FROM bill ORDER BY account_id, bill_period")
            .query((rs, i) -> Map.entry(rs.getLong(1), rs.getObject(2, LocalDate.class))).list().stream()
            .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

        assertThat(periods).containsOnlyKeys(1001L, 1002L, 1003L, 1004L, 1005L, 1006L);
        assertThat(periods.values()).allSatisfy(list -> assertThat(list).hasSize(7)
            .startsWith(LocalDate.of(2026, 3, 1)).endsWith(LocalDate.of(2026, 9, 1)));
        assertThat(jdbc.sql("SELECT count(*) FROM bill_line_item").query(Long.class).single()).isEqualTo(130);
        assertThat(jdbc.sql("SELECT count(*) FROM bill_line_item WHERE category = 'TAX'").query(Long.class).single())
            .isEqualTo(63);
    }

    /** Totals of seed-scenarios.md §5: account, month, supply type, taxable, tax, total. */
    @ParameterizedTest(name = "{0} {1}: total {5}")
    @CsvSource({
            "1001, 2026-03, INTRA, 699.00, 125.82, 824.82",
            "1001, 2026-04, INTRA, 699.00, 125.82, 824.82",
            "1001, 2026-05, INTRA, 699.00, 125.82, 824.82",
            "1001, 2026-06, INTRA, 699.00, 125.82, 824.82",
            "1001, 2026-07, INTRA, 699.00, 125.82, 824.82",
            "1001, 2026-08, INTRA, 699.00, 125.82, 824.82",
            "1001, 2026-09, INTRA, 2474.00, 445.32, 2919.32",
            "1002, 2026-03, INTER, 399.00, 71.82, 470.82",
            "1002, 2026-04, INTER, 399.00, 71.82, 470.82",
            "1002, 2026-05, INTER, 399.00, 71.82, 470.82",
            "1002, 2026-06, INTER, 399.00, 71.82, 470.82",
            "1002, 2026-07, INTER, 399.00, 71.82, 470.82",
            "1002, 2026-08, INTER, 460.44, 82.88, 543.32",
            "1002, 2026-09, INTER, 767.64, 138.18, 905.82",
            "1003, 2026-03, INTRA, 528.50, 95.12, 623.62",
            "1003, 2026-04, INTRA, 528.50, 95.12, 623.62",
            "1003, 2026-05, INTRA, 528.50, 95.12, 623.62",
            "1003, 2026-06, INTRA, 528.50, 95.12, 623.62",
            "1003, 2026-07, INTRA, 528.50, 95.12, 623.62",
            "1003, 2026-08, INTRA, 528.50, 95.12, 623.62",
            "1003, 2026-09, INTRA, 724.50, 130.40, 854.90",
            "1004, 2026-03, INTER, 399.00, 71.82, 470.82",
            "1004, 2026-04, INTER, 399.00, 71.82, 470.82",
            "1004, 2026-05, INTER, 399.00, 71.82, 470.82",
            "1004, 2026-06, INTER, 399.00, 71.82, 470.82",
            "1004, 2026-07, INTER, 399.00, 71.82, 470.82",
            "1004, 2026-08, INTER, 399.00, 71.82, 470.82",
            "1004, 2026-09, INTER, 544.17, 97.95, 642.12",
            "1005, 2026-03, INTRA, 599.00, 107.82, 706.82",
            "1005, 2026-04, INTRA, 599.00, 107.82, 706.82",
            "1005, 2026-05, INTRA, 599.00, 107.82, 706.82",
            "1005, 2026-06, INTRA, 599.00, 107.82, 706.82",
            "1005, 2026-07, INTRA, 599.00, 107.82, 706.82",
            "1005, 2026-08, INTRA, 599.00, 107.82, 706.82",
            "1005, 2026-09, INTRA, 1198.00, 215.64, 1413.64",
            "1006, 2026-03, INTER, 511.00, 91.98, 602.98",
            "1006, 2026-04, INTER, 517.00, 93.06, 610.06",
            "1006, 2026-05, INTER, 505.00, 90.90, 595.90",
            "1006, 2026-06, INTER, 523.00, 94.14, 617.14",
            "1006, 2026-07, INTER, 511.00, 91.98, 602.98",
            "1006, 2026-08, INTER, 517.00, 93.06, 610.06",
            "1006, 2026-09, INTER, 523.00, 94.14, 617.14"
    })
    void billMatchesTheApprovedTotalsAndTheGstRule(long accountId, String month, SupplyType supplyType,
            String taxable, String tax, String total) {
        LocalDate period = LocalDate.parse(month + "-01");
        BillRow bill = jdbc.sql("""
                SELECT bill_id, supply_type, subtotal, tax_total, total FROM bill
                WHERE account_id = ? AND bill_period = ?""")
            .params(accountId, period)
            .query((rs, i) -> new BillRow(rs.getLong(1), SupplyType.valueOf(rs.getString(2)), rs.getBigDecimal(3),
                    rs.getBigDecimal(4), rs.getBigDecimal(5)))
            .single();

        assertThat(bill.supplyType()).isEqualTo(supplyType);
        assertThat(bill.subtotal()).isEqualByComparingTo(taxable);
        assertThat(bill.taxTotal()).isEqualByComparingTo(tax);
        assertThat(bill.total()).isEqualByComparingTo(total);

        // Subtotal = sum of charge lines; TAX lines = the GST rule recomputed (A-72).
        BigDecimal charges = jdbc.sql("""
                SELECT coalesce(sum(amount), 0) FROM bill_line_item
                WHERE bill_period = ? AND bill_id = ? AND category <> 'TAX'""")
            .params(period, bill.billId()).query(BigDecimal.class).single();
        assertThat(charges).isEqualByComparingTo(bill.subtotal());

        List<String> taxLines = jdbc.sql("""
                SELECT tax_component || ' ' || tax_rate || ' ' || amount FROM bill_line_item
                WHERE bill_period = ? AND bill_id = ? AND category = 'TAX' ORDER BY line_item_id""")
            .params(period, bill.billId()).query(String.class).list();
        List<String> expected = gst.compute(Money.of(bill.subtotal()), supplyType).lines().stream()
            .map(SeedDataIT::describe).toList();
        assertThat(taxLines).isEqualTo(expected);
    }

    @Test
    void supplyTypeFollowsTheAccountStateAndTheSupplierRegisteredStates() {
        List<String> mismatches = jdbc.sql("""
                SELECT b.bill_id, b.place_of_supply, b.supply_type, a.gst_state_code
                FROM bill b JOIN account a USING (account_id)""")
            .query((rs, i) -> {
                String state = rs.getString("gst_state_code");
                boolean ok = rs.getString("place_of_supply").equals(state) && SupplyType.valueOf(rs.getString("supply_type"))
                    == SupplyType.of(state, taxProperties.supplierStateCodes());
                return ok ? null : String.valueOf(rs.getLong("bill_id"));
            })
            .list().stream().filter(Objects::nonNull).toList();

        assertThat(mismatches).isEmpty();
        // The seed keeps both paths covered (A-73): 3 intra-state and 3 inter-state accounts.
        assertThat(jdbc.sql("SELECT supply_type, count(DISTINCT account_id) FROM bill GROUP BY supply_type ORDER BY 1")
            .query((rs, i) -> rs.getString(1) + "=" + rs.getLong(2)).list())
            .containsExactly("INTER=3", "INTRA=3");
    }

    @Test
    void theDuplicateRentalOfAccount1005SharesItsExternalReference() {
        assertThat(jdbc.sql("""
                SELECT count(*) FROM bill_line_item
                WHERE bill_period = DATE '2026-09-01' AND account_id = 1005 AND category = 'RENTAL'
                GROUP BY external_ref, service_period_start, service_period_end, amount""")
            .query(Long.class).list()).containsExactly(2L);
    }

    @Test
    void usageChargesEqualTheMatchingLineItems() {
        // ADR-007 §6: DATA -> data_charge, VOICE -> voice_charge, SMS -> sms_charge, ROAMING -> roaming_charge.
        assertThat(jdbc.sql("""
                SELECT count(*) FROM usage_period u
                JOIN bill b ON b.account_id = u.account_id AND b.bill_period = u.billed_period
                CROSS JOIN LATERAL (
                    SELECT coalesce(sum(amount) FILTER (WHERE category = 'DATA'), 0)    AS data,
                           coalesce(sum(amount) FILTER (WHERE category = 'VOICE'), 0)   AS voice,
                           coalesce(sum(amount) FILTER (WHERE category = 'SMS'), 0)     AS sms,
                           coalesce(sum(amount) FILTER (WHERE category = 'ROAMING'), 0) AS roaming
                    FROM bill_line_item l WHERE l.bill_period = b.bill_period AND l.bill_id = b.bill_id) li
                WHERE (u.data_charge, u.voice_charge, u.sms_charge, u.roaming_charge)
                      IS DISTINCT FROM (li.data, li.voice, li.sms, li.roaming)""")
            .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM usage_period").query(Long.class).single()).isEqualTo(42);
    }

    @Test
    void dailyRowsExistOnlyForAugustAndSeptemberAndAddUpToThePeriodRows() {
        assertThat(jdbc.sql("SELECT DISTINCT billed_period FROM usage_daily ORDER BY 1").query(LocalDate.class).list())
            .containsExactly(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM usage_period u
                JOIN (SELECT account_id, billed_period, sum(data_mb) dm, sum(data_charge) dc, sum(voice_min) vm,
                             sum(voice_charge) vc, sum(sms_count) sc, sum(sms_charge) sch, sum(roaming_data_mb) rd,
                             sum(roaming_voice_min) rv, sum(roaming_sms_count) rs, sum(roaming_charge) rc
                      FROM usage_daily GROUP BY account_id, billed_period) d USING (account_id, billed_period)
                WHERE (u.data_mb, u.data_charge, u.voice_min, u.voice_charge, u.sms_count, u.sms_charge,
                       u.roaming_data_mb, u.roaming_voice_min, u.roaming_sms_count, u.roaming_charge)
                      IS DISTINCT FROM (d.dm, d.dc, d.vm, d.vc, d.sc, d.sch, d.rd, d.rv, d.rs, d.rc)""")
            .query(Long.class).single()).isZero();
        // One daily row per day of each usage period (12 account-periods).
        assertThat(jdbc.sql("""
                SELECT count(*) FROM (SELECT DISTINCT account_id, billed_period FROM usage_daily) x""")
            .query(Long.class).single()).isEqualTo(12);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM bill b WHERE b.bill_period IN (DATE '2026-08-01', DATE '2026-09-01')
                AND (b.period_end - b.period_start + 1) <> (SELECT count(*) FROM usage_daily d
                    WHERE d.account_id = b.account_id AND d.billed_period = b.bill_period)""")
            .query(Long.class).single()).isZero();
    }

    @Test
    void theUaeTripIsSevenDaysOfRoamingInAugust() {
        assertThat(jdbc.sql("""
                SELECT min(usage_date), max(usage_date), sum(data_mb), sum(voice_min), sum(sms_count), sum(charge)
                FROM usage_daily_roaming WHERE account_id = 1001 AND country_code = 'AE'""")
            .query((rs, i) -> List.of(rs.getString(1), rs.getString(2), rs.getBigDecimal(3).toPlainString(),
                    rs.getBigDecimal(4).toPlainString(), rs.getString(5), rs.getBigDecimal(6).toPlainString()))
            .single())
            .containsExactly("2026-08-12", "2026-08-18", "550.000", "10.000", "3", "1775.00");
    }

    @Test
    void theRollUpBatchLedgerCountsTheDailyRows() {
        assertThat(jdbc.sql("SELECT batch_id || '=' || row_count FROM usage_ingest_batch ORDER BY batch_id")
            .query(String.class).list())
            .containsExactly("SEED-2026-08=186", "SEED-2026-09=186");
    }

    private static String describe(TaxLine line) {
        return line.component() + " " + line.ratePercent().setScale(2) + " " + line.amount();
    }

    record BillRow(long billId, SupplyType supplyType, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal total) {
    }
}
