package com.telco.billshock;

import com.telco.billshock.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Flyway schema V1–V6: partitions, constraints and the immutable audit log. */
@IntegrationTest
@Transactional   // every test rolls back
class SchemaIT {

    private static final List<String> PARTITIONED = List.of("bill", "bill_line_item", "usage_period",
            "usage_period_roaming", "usage_daily", "usage_daily_roaming", "chat_messages", "llm_call_log",
            "audit_events");

    @Autowired
    JdbcClient jdbc;

    @Test
    void everyPartitionedTableHasMonthlyPartitionsFrom2026_01To2027_12AndNoDefault() {
        for (String table : PARTITIONED) {
            List<String> partitions = jdbc.sql("""
                    SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                    WHERE i.inhparent = ?::regclass AND c.relkind IN ('r', 'p') ORDER BY c.relname""")
                .param(table).query(String.class).list();

            assertThat(partitions).as(table).hasSize(24).first().isEqualTo(table + "_2026_01");
            assertThat(partitions).as(table).last().isEqualTo(table + "_2027_12");
            assertThat(jdbc.sql("SELECT count(*) FROM pg_partitioned_table WHERE partrelid = ?::regclass AND partdefid <> 0")
                .param(table).query(Long.class).single()).as(table + " default partition").isZero();
        }
    }

    @Test
    void auditPartitionBoundsArePinnedToUtc() {
        // pg_get_expr prints timestamptz bounds in the session time zone.
        jdbc.sql("SET LOCAL TIME ZONE 'UTC'").update();
        assertThat(jdbc.sql("SELECT pg_get_expr(relpartbound, oid) FROM pg_class WHERE relname = 'audit_events_2026_09'")
            .query(String.class).single())
            .isEqualTo("FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00')");
    }

    @Test
    void aBillOutsideThePartitionRangeFails() {
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() -> jdbc.sql("""
                INSERT INTO bill (bill_id, account_id, bill_period, period_start, period_end, bill_date, place_of_supply,
                                  supply_type, subtotal, tax_total, total, status, generated_at)
                VALUES (10012801, 1001, DATE '2028-01-01', DATE '2027-12-01', DATE '2027-12-31', DATE '2028-01-01', '27',
                        'INTRA', 699.00, 125.82, 824.82, 'ISSUED', now())""").update())
            .withMessageContaining("no partition");
    }

    @Test
    void aBillWhoseTotalDoesNotAddUpIsRejected() {
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() -> jdbc.sql("""
                INSERT INTO bill (bill_id, account_id, bill_period, period_start, period_end, bill_date, place_of_supply,
                                  supply_type, subtotal, tax_total, total, status, generated_at)
                VALUES (10012610, 1001, DATE '2026-10-01', DATE '2026-09-01', DATE '2026-09-30', DATE '2026-10-01', '27',
                        'INTRA', 699.00, 125.82, 824.83, 'ISSUED', now())""").update());
    }

    @Test
    void aTaxLineNeedsItsComponentAndRate() {
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() -> jdbc.sql("""
                INSERT INTO bill_line_item (line_item_id, bill_id, bill_period, account_id, category, description, amount)
                VALUES (1001260999, 10012609, DATE '2026-09-01', 1001, 'TAX', 'GST', 1.00)""").update());
    }

    @Test
    void auditEventsCannotBeUpdated() {
        insertAuditEvent();

        assertThatExceptionOfType(DataAccessException.class)
            .isThrownBy(() -> jdbc.sql("UPDATE audit_events SET event_type = 'CHANGED'").update())
            .withMessageContaining("append-only");
    }

    @Test
    void auditEventsCannotBeDeleted() {
        insertAuditEvent();

        assertThatExceptionOfType(DataAccessException.class)
            .isThrownBy(() -> jdbc.sql("DELETE FROM audit_events").update())
            .withMessageContaining("append-only");
    }

    private void insertAuditEvent() {
        jdbc.sql("""
                INSERT INTO audit_events (occurred_at, account_id, actor_type, event_type)
                VALUES (TIMESTAMPTZ '2026-09-25 10:00:00+05:30', 1001, 'SYSTEM', 'TEST')""").update();
    }
}
