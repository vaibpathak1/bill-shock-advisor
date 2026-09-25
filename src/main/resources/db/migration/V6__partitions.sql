-- Monthly partitions for every partitioned table, 2026-01 to 2027-12 (Phase 3a answer 1).
-- There is deliberately no DEFAULT partition: a row outside the range fails loudly
-- (data-architecture.md §5). The partition maintenance job that keeps creating partitions
-- 2 months ahead comes later (proactive-worker); until then, extend the range here.

CREATE FUNCTION create_monthly_partitions(parent regclass, first_month date, last_month date)
    RETURNS void
    LANGUAGE plpgsql AS
$$
DECLARE
    month_start date := date_trunc('month', first_month)::date;
    key_type    text;
    lower_bound text;
    upper_bound text;
BEGIN
    -- The partition key's type decides how the bounds are written. timestamptz bounds are
    -- pinned to UTC so they never depend on the session time zone.
    SELECT format_type(a.atttypid, a.atttypmod)
      INTO key_type
      FROM pg_partitioned_table p
      JOIN pg_attribute a ON a.attrelid = p.partrelid AND a.attnum = p.partattrs[0]
     WHERE p.partrelid = parent;

    WHILE month_start <= last_month LOOP
        IF key_type = 'timestamp with time zone' THEN
            lower_bound := quote_literal(to_char(month_start, 'YYYY-MM-DD') || ' 00:00:00+00');
            upper_bound := quote_literal(to_char((month_start + interval '1 month')::date, 'YYYY-MM-DD') || ' 00:00:00+00');
        ELSE
            lower_bound := quote_literal(month_start);
            upper_bound := quote_literal((month_start + interval '1 month')::date);
        END IF;
        EXECUTE format('CREATE TABLE %I PARTITION OF %s FOR VALUES FROM (%s) TO (%s)',
                       parent::text || '_' || to_char(month_start, 'YYYY_MM'),
                       parent, lower_bound, upper_bound);
        month_start := (month_start + interval '1 month')::date;
    END LOOP;
END;
$$;

SELECT create_monthly_partitions(t::regclass, DATE '2026-01-01', DATE '2027-12-01')
FROM unnest(ARRAY['bill', 'bill_line_item',
                  'usage_period', 'usage_period_roaming', 'usage_daily', 'usage_daily_roaming',
                  'chat_messages', 'llm_call_log', 'audit_events']) AS t;
