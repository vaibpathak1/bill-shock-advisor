-- Bill headers and line items (TMF678 replica), range-partitioned by bill_period (monthly).
-- Design: data-architecture.md §4.1, §5, §7.1 (Q1, Q2). GST model: A-72. Owner module: bss.
-- Partitions are created in V6.

CREATE TABLE bill (
    bill_id         bigint        NOT NULL,
    account_id      bigint        NOT NULL REFERENCES account (account_id),
    bill_period     date          NOT NULL CHECK (extract(day FROM bill_period) = 1),
    period_start    date          NOT NULL,
    period_end      date          NOT NULL,
    bill_date       date          NOT NULL,
    place_of_supply char(2)       NOT NULL CHECK (place_of_supply ~ '^[0-9]{2}$'),
    supply_type     text          NOT NULL CHECK (supply_type IN ('INTRA', 'INTER')),
    subtotal        numeric(14,2) NOT NULL,                      -- GST taxable value
    tax_total       numeric(14,2) NOT NULL CHECK (tax_total >= 0),
    total           numeric(14,2) NOT NULL,
    status          text          NOT NULL CHECK (status IN ('ISSUED', 'ADJUSTED', 'CANCELLED')),
    source_version  integer       NOT NULL DEFAULT 1,
    generated_at    timestamptz   NOT NULL,
    PRIMARY KEY (bill_period, bill_id),
    UNIQUE (account_id, bill_period),                            -- Q1: bill history per account
    CHECK (period_end >= period_start),
    CHECK (total = subtotal + tax_total)
) PARTITION BY RANGE (bill_period);

CREATE TABLE bill_line_item (
    line_item_id         bigint        NOT NULL,
    bill_id              bigint        NOT NULL,
    bill_period          date          NOT NULL,
    account_id           bigint        NOT NULL,
    category             text          NOT NULL CHECK (category IN ('RENTAL', 'DATA', 'VOICE', 'SMS', 'ROAMING', 'VAS',
                                                                    'PRORATION', 'ADJUSTMENT', 'TAX', 'CREDIT', 'OTHER')),
    description          text          NOT NULL,                 -- untrusted text (security.md §5)
    usage_period         date,                                   -- set for late charges (ADR-007)
    service_period_start date,
    service_period_end   date,
    subscription_id      text,
    quantity             numeric(14,3),
    unit                 text,
    amount               numeric(14,2) NOT NULL,                 -- before GST; TAX lines carry the tax itself
    tax_component        text          CHECK (tax_component IN ('CGST', 'SGST', 'IGST')),
    tax_rate             numeric(5,2),
    external_ref         text,
    PRIMARY KEY (bill_period, line_item_id),
    FOREIGN KEY (bill_period, bill_id) REFERENCES bill (bill_period, bill_id),
    CHECK ((category = 'TAX') = (tax_component IS NOT NULL)),
    CHECK ((tax_component IS NULL) = (tax_rate IS NULL)),
    CHECK (service_period_end IS NULL OR service_period_start <= service_period_end)
) PARTITION BY RANGE (bill_period);

CREATE INDEX bill_line_item_bill_category_idx ON bill_line_item (bill_id, category);   -- Q2: diffBills
