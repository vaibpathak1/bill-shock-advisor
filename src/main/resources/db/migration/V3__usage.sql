-- Usage layout option C (ADR-007): bill-period aggregates + daily rows (current and previous
-- cycle), all range-partitioned by billed_period (monthly). Every row carries billed_period
-- and usage_period; usage_period < billed_period marks late usage.
-- Design: data-architecture.md §4.3, §5, §7.1 (Q3, Q4, X1). Owner module: bss.
-- Partitions are created in V6.

CREATE TABLE usage_ingest_batch (
    batch_id    text        PRIMARY KEY,             -- BSS id, or SHA-256 of the file
    source      text        NOT NULL CHECK (source IN ('DAILY_FEED', 'LATE_USAGE', 'SEED')),
    checksum    text        NOT NULL,
    status      text        NOT NULL CHECK (status IN ('RECEIVED', 'LOADED', 'REJECTED', 'QUARANTINED')),
    row_count   integer     NOT NULL CHECK (row_count >= 0),
    received_at timestamptz NOT NULL,
    loaded_at   timestamptz
);

CREATE TABLE usage_period (
    account_id        bigint        NOT NULL REFERENCES account (account_id),
    billed_period     date          NOT NULL CHECK (extract(day FROM billed_period) = 1),
    usage_period      date          NOT NULL CHECK (extract(day FROM usage_period) = 1),
    data_mb           numeric(14,3) NOT NULL DEFAULT 0,
    data_charge       numeric(14,2) NOT NULL DEFAULT 0,
    voice_min         numeric(14,3) NOT NULL DEFAULT 0,   -- domestic minutes only
    isd_min           numeric(14,3) NOT NULL DEFAULT 0,   -- international (ISD) minutes (Q-28)
    voice_charge      numeric(14,2) NOT NULL DEFAULT 0,   -- all VOICE line items: domestic + ISD
    sms_count         integer       NOT NULL DEFAULT 0,
    sms_charge        numeric(14,2) NOT NULL DEFAULT 0,
    roaming_data_mb   numeric(14,3) NOT NULL DEFAULT 0,
    roaming_voice_min numeric(14,3) NOT NULL DEFAULT 0,
    roaming_sms_count integer       NOT NULL DEFAULT 0,
    roaming_charge    numeric(14,2) NOT NULL DEFAULT 0,
    source_row_count  integer       NOT NULL DEFAULT 0,
    rolled_up_at      timestamptz   NOT NULL,
    PRIMARY KEY (account_id, billed_period, usage_period),   -- Q3
    CHECK (usage_period <= billed_period)
) PARTITION BY RANGE (billed_period);

CREATE TABLE usage_period_roaming (
    account_id    bigint        NOT NULL,
    billed_period date          NOT NULL,
    usage_period  date          NOT NULL,
    country_code  char(2)       NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    first_day     date          NOT NULL,
    last_day      date          NOT NULL,
    data_mb       numeric(14,3) NOT NULL DEFAULT 0,
    voice_min     numeric(14,3) NOT NULL DEFAULT 0,
    sms_count     integer       NOT NULL DEFAULT 0,
    charge        numeric(14,2) NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, billed_period, usage_period, country_code),
    FOREIGN KEY (account_id, billed_period, usage_period)
        REFERENCES usage_period (account_id, billed_period, usage_period),
    CHECK (first_day <= last_day)
) PARTITION BY RANGE (billed_period);

CREATE TABLE usage_daily (
    account_id        bigint        NOT NULL REFERENCES account (account_id),
    billed_period     date          NOT NULL CHECK (extract(day FROM billed_period) = 1),
    usage_date        date          NOT NULL,
    source_batch_id   text          NOT NULL REFERENCES usage_ingest_batch (batch_id),
    usage_period      date          NOT NULL CHECK (extract(day FROM usage_period) = 1),
    data_mb           numeric(14,3) NOT NULL DEFAULT 0,
    data_charge       numeric(14,2) NOT NULL DEFAULT 0,
    voice_min         numeric(14,3) NOT NULL DEFAULT 0,   -- domestic minutes only
    isd_min           numeric(14,3) NOT NULL DEFAULT 0,   -- international (ISD) minutes (Q-28)
    voice_charge      numeric(14,2) NOT NULL DEFAULT 0,   -- all VOICE line items: domestic + ISD
    sms_count         integer       NOT NULL DEFAULT 0,
    sms_charge        numeric(14,2) NOT NULL DEFAULT 0,
    roaming_data_mb   numeric(14,3) NOT NULL DEFAULT 0,
    roaming_voice_min numeric(14,3) NOT NULL DEFAULT 0,
    roaming_sms_count integer       NOT NULL DEFAULT 0,
    roaming_charge    numeric(14,2) NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, billed_period, usage_date, source_batch_id),   -- Q4, X1
    CHECK (usage_period <= billed_period)
) PARTITION BY RANGE (billed_period);

CREATE TABLE usage_daily_roaming (
    account_id      bigint        NOT NULL REFERENCES account (account_id),
    billed_period   date          NOT NULL,
    usage_date      date          NOT NULL,
    country_code    char(2)       NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    source_batch_id text          NOT NULL REFERENCES usage_ingest_batch (batch_id),
    usage_period    date          NOT NULL,
    data_mb         numeric(14,3) NOT NULL DEFAULT 0,
    voice_min       numeric(14,3) NOT NULL DEFAULT 0,
    sms_count       integer       NOT NULL DEFAULT 0,
    charge          numeric(14,2) NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, billed_period, usage_date, country_code, source_batch_id)
) PARTITION BY RANGE (billed_period);

CREATE TABLE usage_reconciliation_issue (
    issue_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id    bigint      NOT NULL REFERENCES account (account_id),
    billed_period date        NOT NULL,
    category      text        NOT NULL,
    detail        jsonb       NOT NULL,
    detected_at   timestamptz NOT NULL DEFAULT now(),
    resolved_at   timestamptz
);

-- Usage by the cycle in which it happened (late rows included), ADR-007 §7.
CREATE VIEW usage_as_used AS
SELECT account_id,
       usage_period,
       sum(data_mb)           AS data_mb,
       sum(data_charge)       AS data_charge,
       sum(voice_min)         AS voice_min,
       sum(isd_min)           AS isd_min,
       sum(voice_charge)      AS voice_charge,
       sum(sms_count)         AS sms_count,
       sum(sms_charge)        AS sms_charge,
       sum(roaming_data_mb)   AS roaming_data_mb,
       sum(roaming_voice_min) AS roaming_voice_min,
       sum(roaming_sms_count) AS roaming_sms_count,
       sum(roaming_charge)    AS roaming_charge
FROM usage_period
GROUP BY account_id, usage_period;
