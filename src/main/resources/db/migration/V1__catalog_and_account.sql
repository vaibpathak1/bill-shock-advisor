-- Catalogue replica (TMF620) and local account reference.
-- Design: docs/02-design/data-architecture.md §2, §4.1, §4.2. Owner module: bss.
-- Until the first real deployment, V1–V6 are edited in place (Phase 3a decision, answer 3).

CREATE TABLE account (
    account_id     bigint      PRIMARY KEY,
    msisdn         text        NOT NULL UNIQUE,           -- PII: masked everywhere except BSS calls
    bill_cycle_day smallint    NOT NULL CHECK (bill_cycle_day BETWEEN 1 AND 28),
    gst_state_code char(2)     NOT NULL CHECK (gst_state_code ~ '^[0-9]{2}$'),  -- place of supply (A-72)
    status         text        NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE plan (
    plan_id        bigint        PRIMARY KEY,
    code           text          NOT NULL,
    name           text          NOT NULL,
    monthly_rental numeric(14,2) NOT NULL CHECK (monthly_rental >= 0),  -- before GST
    valid_from     date          NOT NULL,
    valid_to       date,
    attributes     jsonb         NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (code, valid_from),
    CHECK (valid_to IS NULL OR valid_to > valid_from)
);

-- One row per plan, usage type and band. included_units NULL = unlimited.
-- unit_price is a rate (₹ per unit), not an amount, so it carries 4 decimals;
-- every charge computed from it is rounded to NUMERIC(14,2).
CREATE TABLE tariff_rate (
    plan_id        bigint        NOT NULL REFERENCES plan (plan_id),
    usage_type     text          NOT NULL CHECK (usage_type IN ('DATA_MB', 'VOICE_MIN', 'SMS', 'ISD_MIN',
                                                               'ROAM_DATA_MB', 'ROAM_VOICE_MIN', 'ROAM_SMS')),
    band           text          NOT NULL CHECK (band IN ('DOMESTIC', 'INTL', 'GCC', 'WORLD')),
    included_units numeric(14,3) CHECK (included_units IS NULL OR included_units >= 0),
    unit_price     numeric(14,4) NOT NULL CHECK (unit_price >= 0),
    unit           text          NOT NULL,
    day_based      boolean       NOT NULL DEFAULT false,   -- re-rating needs daily data (A-55)
    PRIMARY KEY (plan_id, usage_type, band)
);

-- Add-ons can bundle several allowances (for example roaming packs), so the contents are
-- wide columns rather than one usage_type/units pair.
CREATE TABLE add_on (
    add_on_id     bigint        PRIMARY KEY,
    code          text          NOT NULL,
    name          text          NOT NULL,
    price         numeric(14,2) NOT NULL CHECK (price >= 0),   -- before GST
    data_mb       numeric(14,3),
    voice_min     numeric(14,3),
    sms_count     integer,
    validity      text          NOT NULL CHECK (validity IN ('DAYS', 'BILL_CYCLE')),
    validity_days integer       CHECK (validity_days > 0),
    country_group text          CHECK (country_group IN ('GCC', 'WORLD')),   -- NULL = domestic
    valid_from    date          NOT NULL,
    valid_to      date,
    UNIQUE (code, valid_from),
    CHECK ((validity = 'DAYS') = (validity_days IS NOT NULL))
);
