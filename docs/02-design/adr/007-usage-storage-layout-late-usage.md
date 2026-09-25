# ADR-007: Usage storage layout (option C) and late usage handling

| | |
|---|---|
| Status | Accepted (layout: decision Q-6 / A-56; late-usage keys: Phase 2 answer 4) |
| Date | 2026-09-25 |
| Deciders | Repo owner |
| Related | [data-architecture.md](../data-architecture.md) §4–§5, ADR-002, capacity-estimates §5.4, A-24, A-25, A-55–A-57, A-60, A-63 |

## Context

The engines need usage to explain bills: quantities per category (data MB, voice minutes,
SMS, roaming per country), and for day-based tariffs, per-day values. Decision Q3 keeps
usage **aggregates** locally and fetches per-session detail from BSS on demand (A-25).
Decision Q-6 chose layout **option C** (capacity-estimates §5.4):

1. **Bill-period aggregates** for 6 months + current: one wide row per account and period,
   plus a narrow roaming-by-country table.
2. **Daily** wide rows (plus daily roaming by country) only for the current and previous
   cycle.
3. **Per-session detail** from BSS on demand.

Two facts make this harder:

- **Late usage.** Roaming usage arrives through TAP files days to weeks after the trip
  (A-57). The BSS bills those records as **prior-period charges on the next bill**. The
  7th seed scenario (6b) needs the agent to say "this is late roaming from your August
  trip to the UAE", not "you roamed this month".
- **Closed periods.** A bill, once issued, never changes. Corrections arrive as
  adjustments (`bill.adjusted`). Our aggregates must stay consistent with what was
  billed, and a repeated feed must not double-count.

## Decision

### 1. Two period keys on every usage row

Every usage row carries **both**:

| Key | Meaning | Example (Aug trip, billed on the October bill) |
|---|---|---|
| `billed_period` | The billing month of the bill that charges this usage (first day of the month of the bill date) | `2026-10-01` |
| `usage_period` | The billing month of the cycle **in which the usage happened** | `2026-09-01` (the cycle that contained the trip dates) |

For regular usage the two are equal. `usage_period < billed_period` marks **late usage**.

`diffBills` compares bills by `billed_period`. It reports rows with
`usage_period ≠ billed_period` as a separate driver, `LATE_PRIOR_PERIOD_USAGE`, carrying the
original usage period, the country and the amount. It never reports them as new usage in
the current period. The baseline (previous 3 bills) excludes the late rows of those bills,
because late charges are non-recurring.

### 2. Tables

| Table | Grain (primary key) | Partitioned by | Kept |
|---|---|---|---|
| `usage_daily` | `(account_id, billed_period, usage_date, source_batch_id)` | `billed_period`, monthly | **3 partitions** (A-60): the open billing month, the previous one, and one more |
| `usage_daily_roaming` | `(account_id, billed_period, usage_date, country_code, source_batch_id)` | `billed_period`, monthly | 3 partitions |
| `usage_period` | `(account_id, billed_period, usage_period)` | `billed_period`, monthly | 7 partitions (6 months + current, A-07) |
| `usage_period_roaming` | `(account_id, billed_period, usage_period, country_code)` | `billed_period`, monthly | 7 partitions |
| `usage_ingest_batch` | `batch_id` | not partitioned (small: ~a few thousand rows/month) | 13 months |

Column sets are in data-architecture.md §4.3. Money columns are `NUMERIC(14,2)`;
quantities are `NUMERIC(14,3)` or `integer`.

### 3. (a) Which period each table is partitioned by, and why

**All usage tables are partitioned by `billed_period`, not by `usage_date` or
`usage_period`.** Reasons:

1. **Every consumer asks by bill.** `diffBills`, `AnomalyDetector`, the proactive
   diagnosis and `simulatePlans` all start from a bill (`account_id` + `billed_period`),
   so partition pruning always applies.
2. **Late records always land in the open partition.** A TAP record for an old trip is
   billed on the next bill, so its `billed_period` is the open billing month. **No write
   ever touches a closed or dropped partition**, however old the usage itself is. Closed
   partitions are effectively read-only, which also keeps vacuum and backup cheap.
3. **Retention matches bills.** `bill` and `bill_line_item` are partitioned by the same
   billing month, so the retention job drops the same months for bills and their usage
   rows. No orphan usage exists for a bill that is gone.
4. **Late rows stay with the bill that charged them.** The late-usage explanation for the
   October bill is in October's partition, next to October's line items.

Partitioning by `usage_date` was rejected: a late record for a trip 5 weeks ago would
have to be written into an older partition, possibly one already dropped under the
3-partition daily retention. That would force either a longer daily retention (more
storage) or special-case handling.

### 4. Idempotent ingest (batch-id idempotency)

- Every BSS delivery (the nightly daily feed, A-24/A-63, and each TAP-derived late batch) has a
  **`batch_id`**: the BSS-provided id, or the SHA-256 of the file if none is provided. It is
  recorded in `usage_ingest_batch` with a checksum, source (`DAILY_FEED` / `LATE_USAGE`),
  row count and status.
- **Loading a batch replaces that batch's rows.** In one transaction: `DELETE … WHERE
  source_batch_id = :batch` (in the target partition), then insert the batch's rows.
  Applying the same batch twice gives the same result.
- **Same `batch_id`, different checksum →** status `QUARANTINED`. The batch is not loaded, an
  alert fires, and billing ops investigate. A changed file never silently overwrites
  loaded data.
- **Replay after the rows are gone** (the partition was dropped): the ledger still says
  `LOADED`, so the batch is skipped. Idempotency does not depend on the data still being
  there.

### 5. Idempotent roll-up (daily → period) at bill generation

When `bill.generated` arrives for `(account, B)` (proactive-worker, ADR-006), the roll-up
**recomputes** the period rows for `billed_period = B` from that account's daily rows in
partition B, grouped by `usage_period`:

```sql
-- sketch; real SQL in Phase 7
INSERT INTO usage_period (account_id, billed_period, usage_period, data_mb, data_charge, …)
SELECT account_id, billed_period, usage_period, SUM(data_mb), SUM(data_charge), …
FROM usage_daily
WHERE account_id = :acct AND billed_period = :b
GROUP BY account_id, billed_period, usage_period
ON CONFLICT (account_id, billed_period, usage_period)
DO UPDATE SET data_mb = EXCLUDED.data_mb, data_charge = EXCLUDED.data_charge, …;  -- replace, never add
-- then delete period rows for (acct, b) whose usage_period no longer has daily rows
```

It is a **pure function of the retained daily rows**, so running it twice (event
redelivery, or a re-run after `bill.adjusted`) gives the same rows. It never increments.

### 6. Reconciliation (data-quality control, R-15)

After each roll-up, per `(account, B)`:
- Σ period-row charges per category = Σ bill line items of that category (for example,
  roaming charges vs `ROAMING` line items; late rows vs line items whose `usage_period` is
  set). Tolerance: **exact** (₹0.00).
- A mismatch writes a `usage_reconciliation_issue` row and increments
  `usage_reconciliation_mismatch_total`. **Amounts in every explanation come from bill
  line items**, which are authoritative for money. Usage aggregates only explain
  quantities. A mismatch therefore degrades the explanation ("usage detail unavailable
  for this charge"), never the ₹ figures.

### 7. (b) A late record older than the daily table's retention (period table only)

"Older than the daily retention" means the usage happened in a cycle whose own daily
rows have already been dropped. Example: in December, a late TAP record arrives for a
trip that fell in the cycle billed on the **August** bill (`usage_period = 2026-08-01`).
Only the November–January daily partitions are retained, so August's daily rows are gone
and August exists only as `usage_period` rows.

| Step | What happens |
|---|---|
| Ingest | The record goes into the **open** daily partition (`billed_period` = December, or January if December's bill has already run), with `usage_date` in August and `usage_period = 2026-08-01`. No old partition is needed |
| Roll-up | At December's bill run, it becomes a **new period row** `(account, billed_period = Dec, usage_period = Aug)`. August's own period row (`billed_period = Aug`) is **never modified**: it is the as-billed record of the August bill |
| Explanation | `diffBills` on the December bill shows `LATE_PRIOR_PERIOD_USAGE`: usage period August, country (from `usage_period_roaming`), amount (from the line item). While December's daily partition is retained, the per-day dates of the late usage are also available |
| "As used" view of August | Σ of all `usage_period` rows with `usage_period = Aug` across billed periods (the August row + the December late row). This comes from the **period table only**; no daily rows are needed. Offered as the view `usage_as_used` |
| Idempotency afterwards | Recomputing the December late row is possible while December's daily partition is retained (3 months). After that, the December period row is final, and re-delivery of the same batch is blocked by the ledger |
| Older than the period table's retention (> 7 months) | Still stored as a late row in the open partition (the bill that charges it). The "as used" view of that old `usage_period` is incomplete, because the original rows are dropped. The engines never compare against it; the explanation uses the late row and its line item only |
| Detail beyond retention | Per-session records come from BSS on demand (A-25). If BSS cannot serve them, the tool says so; it never estimates |

A **corrected** batch (new `batch_id`) that targets a billed period whose bill has already
been issued is **not applied**. BSS corrects issued bills through adjustments
(`bill.adjusted` + adjustment line items), which trigger a re-roll-up of that period
while its daily partition is still retained. Otherwise the batch is quarantined for
billing ops.

### 8. Assumptions introduced

- **A-66:** the BSS daily feed and the TAP-derived late batches carry, per record, the
  **billed period** (or cycle id) and the **original usage date**. Late line items on
  the bill identify the original usage period (for example through the rated charge's
  coverage period in TMF678). If the feed has no billed period, we derive it from the
  account's bill cycle day: usage dated before the open cycle's start gets the open
  billing month. **Validate with the BSS / roaming team.**
- **A-67:** one postpaid line (MSISDN) per account in v1. Multi-line and family accounts
  would add `line_id` to the usage keys. They are out of scope (enterprise accounts are
  out of scope per SPEC §1.2).

## Consequences

- Positive: storage about 0.25 TB at 1x instead of 4.83 TB (option A). No usage table
  needs sharding at 10x (data-architecture.md §8–§9).
- Positive: scenario 7 is structurally supported: late usage is a first-class row with
  its own period key, not a guess.
- Positive: retention is a `DROP TABLE` on a partition, with no scans or deletes.
- Negative: two granularities must stay consistent. That is handled by the roll-up (a
  recompute) and the exact reconciliation check.
- Negative: an "as used" view of a period can change after that period's bill (late
  rows). Only the as-billed view is used for bill comparisons; the as-used view is
  informational.
- Fall-back (Q-6): if the roll-up cannot be made reliable, option B (wide daily rows for
  all 7 months) is the fall-back. It costs ~0.53 TB instead of ~0.25 TB at 1x.
