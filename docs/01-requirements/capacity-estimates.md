# Capacity Estimates

| | |
|---|---|
| Status | Draft for review (Phase 1) |
| Date | 2026-09-25 |
| Spec reference | SPEC.md §1.4, §2.3, §2.4 |
| Inputs | [assumptions.md](assumptions.md). Every input below is an **ASSUMPTION** with an ID |

All figures are planning estimates derived step by step from the assumptions register.
Change an input there and re-derive; nothing here is measured yet. Measurements replace
these numbers in Phase 10 (Gatling) and after launch.

Three load points are used throughout:

| Load point | Meaning |
|---|---|
| **1x** | Launch-year design peak, derived from A-01 to A-11 |
| **3-yr** | 1x × 1.728 (20% YoY for 3 years, A-05) |
| **10x** | 10 × the 1x design peak (headroom test target, A-06) |
| *Stress* | The SPEC 1.4 figure of 40 turns/s and 3,000 concurrent sessions (A-04). Used for stress tests only, not for sizing |

> **Correction note.** An earlier draft used 40 turns/s as the chat design peak. That
> contradicts the chat demand assumption (A-09): 2M × 1% × 6 turns = 120k turns/day gives
> about 5 turns/s at the peak hour. The design peak is now derived from A-09 to A-11, and
> 40 turns/s is kept as the stress scenario only.

---

## 1. Bill and anomaly volumes

| Quantity | Derivation | 1x | 3-yr | 10x |
|---|---|---|---|---|
| Bills per cycle day | 10M ÷ 5 (A-01, A-02) | 2,000,000 | 3,456,000 | 20,000,000 |
| Flagged bills per cycle day | × 4% (A-03) | 80,000 | 138,240 | 800,000 |
| Proactive diagnoses per month | × 5 cycles | 400,000 | 691,200 | 4,000,000 |
| `bill.generated` average rate | bills ÷ 6 h window (A-08) | 93 /s | 160 /s | 926 /s |
| `bill.generated` burst rate | × 3 (A-08) | 278 /s | 480 /s | 2,778 /s |
| Proactive diagnosis rate needed | flagged ÷ 6 h | 3.7 /s (222 /min) | 6.4 /s (384 /min) | 37 /s (2,222 /min) |

## 2. Chat demand

| Quantity | Derivation | 1x |
|---|---|---|
| Conversations on a peak day | 2M bills × 1% (A-09) | 20,000 |
| Turns on a peak day | × 6 turns | **120,000** |
| Conversations on an off-peak day | 2M × 0.3% (A-09) | 6,000 |
| Turns on an off-peak day | × 6 | 36,000 |
| Peak days per month | 5 cycles × 2 days | 10 |
| Conversations per month | 10 × 20,000 + 20 × 6,000 | **320,000** |
| Turns per month | × 6 | **1,920,000** |

Both chat rates in A-09 are **per bill in one cycle-day's population (2M bills), per
day**. The off-peak 0.3% equals 0.06% of all subscribers per day. The peak-day figure
already includes background traffic from other cycles.

## 3. Chat peak (design peak, corrected)

| Step | Derivation | 1x | 3-yr | 10x | *Stress (A-04)* |
|---|---|---|---|---|---|
| Peak-hour turns | 120,000 × 15% (A-10) | 18,000 /h | 31,104 /h | 180,000 /h | — |
| Peak-hour average | ÷ 3,600 | **5.0 turns/s** | 8.6 turns/s | 50 turns/s | 40 turns/s |
| Peak-minute burst | × 1.5 (A-11) | **7.5 turns/s = 450 /min** | 13.0 /s = 778 /min | 75 /s = 4,500 /min | 40 /s = 2,400 /min (no extra burst applied) |
| Concurrent conversations | peak-hour conversations (3,000/h at 1x) × 8 min ÷ 60 (A-12) | 400 | 691 | 4,000 | 3,000 |
| In-flight SSE streams | burst turns/s × 10 s (A-13) | 75 | 130 | 750 | 400 |

The SPEC's stress figure (40 turns/s, 3,000 concurrent sessions) is about **5x the 1x
peak-hour average**. It sits between the 3-yr and 10x load points, which makes it a
sensible stress target.

## 4. LLM demand

### 4.1 Per-unit token profile

| Unit | Model | Input tokens | Output tokens | Source |
|---|---|---|---|---|
| Chat LLM call | `claude-sonnet-5` | 12,000 | 250 | A-15 |
| Chat turn (3 round trips) | `claude-sonnet-5` | 36,000 | 750 | A-14 × A-15 |
| Proactive diagnosis (1 call) | current Haiku-tier model (config) | 6,000 (2,000 cacheable) | 600 | A-17 |

Only cache writes and uncached tokens count toward ITPM (A-21). For chat that is 25% of
input tokens (A-16). For proactive it is the 4,000 non-cached tokens.

### 4.2 LLM tokens per day

| Day type | Chat input | Chat output | Proactive input | Proactive output |
|---|---|---|---|---|
| Peak chat day (120k turns) | 4.32 B | 90 M | — | — |
| Off-peak chat day (36k turns) | 1.30 B | 27 M | — | — |
| Cycle day (80k diagnoses) | — | — | 480 M | 48 M |
| **Worst-case overlap** (SPEC 7 load simulation: cycle day + chat peak) | 4.32 B | 90 M | 480 M | 48 M |
| **Per month** | 69.1 B | 1.44 B | 2.40 B | 240 M |

"Input" is total prompt tokens, including cache reads. With A-16, 75% of chat input is
billed at the cache-read rate.

### 4.3 Chat requests and tokens per minute versus rate limits (`claude-sonnet-5`)

LLM requests per minute = turns/min × round trips per turn (A-14).
Counted ITPM = RPM × 12,000 × 25% (A-16). Uncached ITPM is shown to demonstrate why caching
is required.

| Load point | Turns/min | RPM | Counted ITPM (cached) | ITPM with no caching | OTPM |
|---|---|---|---|---|---|
| **1x** (3 round trips) | 450 | 1,350 | **4.05 M** | 16.2 M | 338 k |
| 1x, p95-heavy (5 round trips) | 450 | 2,250 | 6.75 M | 27.0 M | 563 k |
| **3-yr** | 778 | 2,333 | **7.00 M** | 28.0 M | 583 k |
| **10x** | 4,500 | 13,500 | **40.5 M** | 162 M | 3.38 M |
| *Stress* (3 round trips) | 2,400 | 7,200 | 21.6 M | 86.4 M | 1.80 M |
| *Stress* (5 round trips) | 2,400 | 12,000 | 36.0 M | 144 M | 3.00 M |

Utilisation against the **Scale tier** (10,000 RPM / 10 M ITPM / 2 M OTPM for Sonnet 5, A-21).
Planning rule: keep the peak minute at **≤ 70%** of any limit.

| Load point | RPM % | ITPM % | OTPM % | Verdict |
|---|---|---|---|---|
| 1x | 14% | **41%** | 17% | Fits Scale tier. Build tier (5 M ITPM) would be at 81%: too tight |
| 1x without prompt caching | 14% | **162%** | 17% | **Does not fit.** Prompt caching is a hard requirement |
| 1x, p95-heavy | 23% | 68% | 28% | Fits, at the edge of the 70% rule |
| 3-yr | 23% | **70%** | 29% | At the edge. **Custom limits needed by year 3**, earlier if round trips drift up |
| 10x | 135% | **405%** | 169% | **Custom limits required.** At the 70% rule: ≈ 58 M ITPM, 19k RPM, 4.8 M OTPM |
| Stress | 72%–120% | **216%–360%** | 90%–150% | **Custom limits required** to run the stress test against the real provider. Use the stubbed LLM instead (SPEC 5) |

### 4.4 Proactive requests and tokens per minute (current Haiku-tier model (config))

RPM = diagnoses per minute (1 call each). Counted ITPM = RPM × 4,000.

| Load point | RPM | Counted ITPM | OTPM | Scale-tier ITPM % | Verdict |
|---|---|---|---|---|---|
| 1x | 222 | 0.89 M | 133 k | 9% | Fits easily (even Start tier) |
| 3-yr | 384 | 1.54 M | 230 k | 15% | Fits |
| 10x | 2,222 | 8.89 M | 1.33 M | **89%** | **Custom limits**, or stretch the window (the 6 h NFR allows only limited stretch), or use the Batch API. At 10x, the Batch API queue (800k requests per cycle day) also exceeds Scale's 500k queue limit |

Haiku and Sonnet have **separate** rate limits (A-21), so the proactive batch cannot use
up live chat's model quota. They still share the organisation-level spend cap. Workspace
limits enforce the SPEC 2.3 "separate quotas".

### 4.5 Monthly spend versus the tier spend cap

From [plan-and-budget.md](plan-and-budget.md) §3: LLM spend is **$69.1k/month at 1x**,
$119k at 3-yr and $207k at 3x load. The Scale tier's **$200,000/month spend cap** is
reached at about **2.9x** load. Beyond that, a Custom tier or enterprise agreement is
needed.

### 4.6 Where custom (negotiated) limits are needed

| Trigger | When | Action |
|---|---|---|
| Sonnet 5 ITPM > 70% of Scale | ~Year 3 at 20% growth, or earlier if chat rate > 1.2% (A-09) or round trips > 3.5 | Negotiate custom ITPM/RPM with the Anthropic account team **6 months ahead** |
| Monthly LLM spend > $200k | ~2.9x load | Enterprise agreement (also opens volume discounts) |
| 10x headroom test with the real provider | Phase 10 / staging | Temporary custom limits, or test with the stubbed LLM (default per SPEC 5) |
| Proactive at 10x | 10x | Custom Haiku limits or Batch queue increase |

**Conclusion for the risk register:** with prompt caching, rate limits are **not** a
launch blocker at 1x (41% of Scale-tier ITPM). They are a medium-term planning item, and
a blocker only at 10x or under the stress scenario. Prompt caching itself is a hard
requirement. Without it, 1x does not fit.

### 4.7 Sensitivity of the chat peak to the chat rate (A-09)

| Chat rate on peak days | Peak burst turns/s | Counted ITPM (1x) | Scale ITPM % |
|---|---|---|---|
| 0.5% | 3.75 | 2.0 M | 20% |
| **1% (baseline)** | 7.5 | 4.05 M | 41% |
| 2% | 15 | 8.1 M | **81%** (custom limits needed at launch) |

## 5. Storage

### 5.1 Usage data (hybrid design, decision Q3)

**Part 1: daily rated aggregates, stored locally for all subscribers (A-23, A-24)**

| Quantity | Derivation | 1x |
|---|---|---|
| Rows per day | 10M subscribers × 10 rows | 100 M |
| Rows per month | × 30 | 3.0 B |
| Size per month | × 230 B (A-23) | **690 GB** |
| Retained | 7 monthly partitions (A-07, A-29) | 21 B rows, **4.83 TB** |
| Nightly load rate | 100 M ÷ 4 h (A-24) | 6,944 rows/s (daily average 1,157 rows/s) |
| Range at 8–15 rows/day | | 3.9–7.2 TB |

> **Design option for Phase 2:** a wide row per subscriber-day (one column group per usage
> type) instead of one row per type would cut the row count by ~10x and index size by
> about the same. This is recorded as an open question for the data architecture. The
> numbers above use the narrow layout, which is the conservative case.

**Part 2: detailed usage, fetched on demand and cached per conversation (A-25)**

| Quantity | Derivation | 1x | 10x |
|---|---|---|---|
| Peak-hour conversations | 20,000 × 15% | 3,000 /h | 30,000 /h |
| Accounts with detail fetched | × 50% | 1,500 /h → 0.42 BSS calls/s | 4.2 calls/s |
| Accounts resident in Redis | 1,500/h × 2 h TTL | 3,000 | 30,000 |
| Redis memory | × 2,000 records × 200 B = 400 KB | **1.2 GB** | 12 GB |
| PostgreSQL storage | not persisted (only references in audit) | 0 | 0 |

### 5.2 Other tables

| Table | Monthly rows (1x) | Monthly size | Retention (A-29) | Retained size (1x) |
|---|---|---|---|---|
| `bills` | 10 M | 5 GB | 7 months | 35 GB |
| `bill_line_items` | 150 M (15 per bill, A-26) | 30 GB | 7 months | 210 GB |
| `audit_events` | 21.2 M (1.92M turns × 10 + 400k diagnoses × 5, A-27) | 17 GB | 13 months hot | 220 GB |
| `chat_messages` | 7.68 M (4 per turn, A-28) | 11.5 GB | 90 days hot | 35 GB |
| Chat memory per conversation | 6 turns × 4 × 1.5 KB | 36 KB each | — | — |
| Other tables (A-49) | — | — | — | 50 GB |

### 5.3 Totals and growth over 3 years

> Option A upper bound. Option C: 0.82 TB at launch, 1.42 TB at end of year 3, 8.2 TB at 10x
> ([data-architecture.md §9](../02-design/data-architecture.md)).

Primary database, steady state (excluding the Multi-AZ standby and the read replica, each
a full copy):

| | Launch (Y0) | End Y1 | End Y2 | End Y3 | 10x |
|---|---|---|---|---|---|
| Subscribers | 10.0 M | 12.0 M | 14.4 M | 17.3 M | — |
| Usage aggregates | 4.83 TB | 5.80 TB | 6.96 TB | 8.35 TB | 48.3 TB |
| Bills + line items | 0.25 TB | 0.29 TB | 0.35 TB | 0.42 TB | 2.5 TB |
| Audit (13 months) | 0.22 TB | 0.26 TB | 0.32 TB | 0.38 TB | 2.2 TB |
| Chat (90 days) + other | 0.09 TB | 0.10 TB | 0.12 TB | 0.15 TB | 0.9 TB |
| **Total primary** | **5.4 TB** | **6.5 TB** | **7.8 TB** | **9.3 TB** | **54 TB** |

Because every table has a fixed retention, database size grows with the subscriber base
(×1.2 per year), not with time.

**Object storage (S3; MinIO locally), cumulative at end of year 3:**
- Audit partitions archived after 13 months: about 23 months × ~17–29 GB ≈ **550 GB**
  uncompressed (~110 GB compressed columnar).
- Chat transcripts, rolling 1 year: ≈ **240 GB** at year 3.
- Usage partitions are dropped, not archived (BSS is the system of record).
- Total **< 1 TB**. S3 is not a cost driver.

### 5.4 Usage storage layout: options compared (decision Q-6)

> §5.1, §5.3 and §6 were derived with **option A** (narrow daily rows). The chosen layout
> is **option C**, which is much smaller. Those sections are kept as the conservative
> upper bound. **Phase 2 update:** storage and IOPS were re-derived with option C in
> [data-architecture.md §9](../02-design/data-architecture.md): **≈ 0.82 TB and ≈ 1,070
> peak IOPS at 1x; ≈ 8.2 TB and ≈ 10,700 IOPS at 10x, with no sharding needed.** The
> summary in §10 uses these figures.

**Step 1: what granularity does each consumer actually need?**

| Consumer | What it computes | Granularity needed | Window needed |
|---|---|---|---|
| `diffBills(currentPeriod, baselineMonths=3)` | Per-category delta, current period vs 3-period baseline, ranked by contribution | **Bill period.** Amounts come from bills/line items; usage quantities per period give the explanation | Current + 3 previous periods |
| `AnomalyDetector` (at bill generation) | Is this bill unusual versus the subscriber's history? | **Bill period** (totals and per-category, for baseline statistics) | Current + 6 previous periods |
| `simulatePlans(billPeriod)` | Re-rate one period's actual usage against the catalog | **Bill period** for monthly pools. **Daily** for day-based tariff features: daily data caps and per-day roaming packs (which also need the country per day) | Only the period being simulated. In practice current or previous |
| `getUsageDetails(billPeriod, type)` | Per-session records (timestamp, country, volume) | **Per session** | Any period; from BSS on demand (A-25) |
| Explanation text (LLM + templates) | "Roaming in the UAE from 3–9 Aug added ₹X" | **Bill period** for amounts; **daily** for "when" | Current period (sometimes previous) |
| `getBillHistory(6)` | Bill totals for 6 months | Bill-level | From `bills`, not usage tables |

**Does any consumer need 6 months of daily data? No, not in v1.** The only edge case is
`simulatePlans` for a period **older than the previous cycle**, under a tariff with
day-based features (daily caps or per-day roaming packs). Period totals cannot re-rate
that exactly, and money must never be approximated. In that case the simulator fetches
per-session detail from BSS on demand and aggregates it in Java (A-55). If BSS cannot
serve it, the tool returns "simulation not available for that period" instead of
guessing. Time-of-day tariffs (such as free night data) would need hourly buckets. They
are assumed not to be in the v1 catalog; flagged for the Phase 2 data architecture.

**Step 2: the three options**

| | **A. Narrow daily** (row per subscriber-day-type) | **B. Wide daily** (row per subscriber-day + narrow daily roaming-by-country) | **C. Period aggregates + short daily + BSS detail** |
|---|---|---|---|
| Rows kept (1x) | 21.0 B (10 × 30 days × 7 months) | 2.1 B | Period: 70 M wide + ~1.7 M roaming. Daily: 0.6–0.9 B for the current + previous cycle |
| **Usage storage (1x)** | **4.83 TB** | **0.53 TB** | **0.17–0.25 TB** (0.02 TB period + 0.15–0.23 TB daily; the range depends on partition granularity: exact 2 cycles vs 3 monthly partitions) |
| Primary DB total (1x, with bills, audit, chat) | 5.4 TB | 1.1 TB | **0.7–0.8 TB** |
| Primary DB total (10x) | 54 TB | 11 TB | **7–8 TB** (bills and audit now dominate) |
| Nightly feed rows/s (4 h window) | 6,944 | 698 (10 M wide + 56 k roaming rows/day) | 698 |
| Feed write IOPS | 1,389 | 140 | 140 |
| Period roll-up at bill generation | none (aggregated at query time) | none (aggregated at query time) | ~205 IOPS during the bill run (read ~30 daily rows, write 1 period row per bill; can share the anomaly-screening read) |
| Proactive diagnosis read | ~1,800 rows, ~52 pages → 192 IOPS | ~180 rows, ~6 pages → 22 IOPS | ~7 period rows + ≤ 30 daily rows, ~3 pages → 11 IOPS |
| **Estimated peak IOPS (1x, worst-case overlap as §6)** | **~2,300** | **~880** | **~1,070** |
| Estimated peak IOPS (10x) | ~23,000 (sharding zone) | ~8,800 | ~10,700 |
| `diffBills` / anomaly query | `SUM … GROUP BY period, type` over ~1,800 rows, with cycle-boundary logic in SQL | `SUM` over ~180 rows + roaming join, with cycle-boundary logic | **Direct read of 4–7 precomputed period rows**; no aggregation at query time; the cycle boundary is fixed once at roll-up (handles mid-cycle plan changes, scenario 4) |
| `simulatePlans` | Period sums or daily rows, any period | Period sums or daily rows, any period | Period row; daily rows for current/previous; **BSS on demand for older periods with day-based tariffs** |
| Explanation "when" | Daily rows | Daily rows | Daily rows (current/previous) |
| Complexity cost | Simplest schema; heaviest queries and storage; retention by dropping partitions | One wide table + one roaming table; moderate queries | **Two granularities to keep consistent.** Needs a roll-up step, a reconciliation check (Σ daily = period row; period amounts vs bill line items) and a slower BSS path for rare old-period cases |
| Sharding needed at 10x? | Yes | Probably not (storage); IOPS borderline | No (storage ~8 TB; IOPS within one large instance) |

**Step 3: recommendation → option C (decided 2026-09-25, A-56).**
- Every main consumer (diff, anomaly, proactive, most simulations) reads **precomputed
  bill-period rows**. That is the granularity the product reasons in.
- Usage storage is **~2–3x smaller than B and ~20–28x smaller than A** (0.17–0.25 TB vs
  0.53 TB vs 4.83 TB). It also removes the 10x sharding trigger for usage data.
- IOPS are close to B (slightly higher because of the roll-up at bill generation) and
  far below A.
- The extra complexity is bounded. The roll-up happens where the bill is processed
  anyway. The reconciliation check doubles as a data-quality control (R-15). The BSS
  fall-back is needed only for a rare case (A-55).
- Option B is the fall-back if Phase 2 finds that the roll-up cannot be made reliable,
  for example if late CDRs keep changing closed periods.

Open points for the Phase 2 data architecture: the exact column set of the wide rows;
how late CDRs update a closed period (re-roll-up vs adjustment rows); partition
granularity for the daily table (weekly vs monthly, which sets the 0.15–0.23 TB range);
and the time-of-day tariff flag above.

## 6. Database IOPS

> Option A upper bound. The option C figures (≈ 1,070 at 1x, ≈ 10,700 at 10x) are in
> [data-architecture.md §9](../02-design/data-architecture.md).

Using the I/O factors in A-30. The worst-case minute has the nightly usage load, a bill
run and the chat peak all at once. That is pessimistic, since the usage feed runs at
night and the chat peak is by day.

| Workload | Rate (1x) | I/O per unit | IOPS (1x) | Primary / replica |
|---|---|---|---|---|
| Nightly usage aggregate load | 6,944 rows/s | 0.2 per row | 1,389 | Primary (write) |
| Bill ingest (2M bills + 30M line items in 6 h) | 1,481 rows/s | 0.2 per row | 296 | Primary (write) |
| Anomaly screening of every bill (6 prior bills + line items) | 93 bills/s | ~3 pages | 280 | Replica* |
| Proactive diagnosis (180 days × 10 aggregate rows) | 3.7 /s | ~52 pages | 192 | Replica* |
| Proactive writes (diagnosis, audit, notification, outbox) | 3.7 /s × 8 rows | 0.2 per row | 6 | Primary |
| Chat reads (history, bills, catalog misses) | 7.5 turns/s | ~15 pages | 113 | Replica |
| Chat writes (messages, audit, outbox) | 7.5 turns/s × 15 rows | 0.2 per row | 23 | Primary |
| **Total** | | | **≈ 2,300** (≈1,700 write + 600 read) | |

\* Reading a just-ingested bill from the replica risks replica lag. Phase 2 decides
whether the worker reads from the primary or waits for replica lag < threshold.

| Load point | Estimated peak IOPS | Provision (2x headroom) |
|---|---|---|
| 1x | 2,300 | ≥ 5,000 |
| 3-yr | 4,000 | ≥ 8,000 |
| 10x | 23,000 | ≥ 46,000, together with 54 TB storage. **This is the sharding trigger zone** (SPEC 2.2; the path is designed in Phase 2) |

## 7. Kafka throughput

Message sizes are assumed: ~1 KB for events, ~2 KB for anomaly events carrying a summary.
Partition key: `account_id` (SPEC 2.3).

| Topic | 1x average | 1x peak | 10x peak | Peak MB/s at 10x | Proposed partitions |
|---|---|---|---|---|---|
| `bill.generated` | 93 /s | 278 /s | 2,778 /s | 2.8 | 24 |
| `bill.anomaly.detected` | 3.7 /s | 11 /s | 111 /s | 0.2 | 12 |
| `notification.created` | 3.7 /s | 11 /s | 111 /s | 0.1 | 12 |
| `action.executed` | 0.1 /s | 0.4 /s | 4 /s | <0.01 | 12 |
| DLQ per topic | ~0 | — | — | — | 3 each |

`action.executed` assumes ~0.5 actions per conversation. Total at 10x is under 4 MB/s,
well within a minimal 3-broker cluster. Partition counts are set for **consumer
parallelism** (up to 24 worker threads on `bill.generated`), not bandwidth.
Replication factor 3, retention 7 days. Storage on `bill.generated` is about 2 GB per
cycle day × RF 3, which is negligible.

## 8. Redis sizing

| Content | 1x | 10x |
|---|---|---|
| Diagnosis per `bill_id` (400k × 5 KB, TTL 35 days, A-48) | 2.0 GB | 20 GB |
| Detailed usage per conversation (§5.1) | 1.2 GB | 12 GB |
| Bill summaries (TTL 15 min) | < 0.5 GB | < 5 GB |
| Rate-limit buckets, idempotency keys | < 0.2 GB | < 2 GB |
| **Total** | **≈ 4 GB** | **≈ 37 GB** (cluster mode) |

## 9. Pod counts

**`chat-api`** (A-31): pods = max(3, ⌈burst turns/s ÷ (20 × 60%)⌉), and the SSE check is
in-flight streams ÷ 500 per pod.

| Load point | Burst turns/s | CPU-driven | SSE-driven | **Pods** |
|---|---|---|---|---|
| 1x | 7.5 | 1 | 1 | **3** (HA floor) |
| 3-yr | 13 | 2 | 1 | **3** |
| 10x | 75 | 7 | 2 | **7** |
| Stress | 40 | 4 | 1 | **4** |

**`proactive-worker`** (A-18, A-32): concurrent LLM calls = diagnoses/s × 8 s; pods = ÷ 20.

| Load point | Diagnoses/s | Concurrent LLM calls | **Pods** |
|---|---|---|---|
| 1x | 3.7 | 30 | **2** (min 1 outside cycle days, KEDA on lag) |
| 3-yr | 6.4 | 51 | **3** |
| 10x | 37 | 296 | **15** (or 6 with a semaphore of 50, if the rate limit allows) |

A **global** LLM concurrency ceiling per workspace is derived from the negotiated limit
(for example, Scale Haiku 10k RPM × 8 s ÷ 60 ≈ 1,300 concurrent at most). Each pod's
semaphore is a share of that ceiling.

## 10. Summary

| Dimension | 1x | 3-yr | 10x | Key takeaway |
|---|---|---|---|---|
| Chat peak (burst) | 7.5 turns/s | 13 turns/s | 75 turns/s | Stress 40 turns/s sits between 3-yr and 10x |
| Sonnet 5 counted ITPM | 4.05 M | 7.0 M | 40.5 M | Caching is required; custom limits by year 3 |
| Haiku counted ITPM | 0.89 M | 1.54 M | 8.9 M | Fine until 10x |
| LLM tokens / busiest day | 4.8 B in / 138 M out | 8.3 B / 238 M | 48 B / 1.4 B | |
| DB primary size (option C, Phase 2) | 0.82 TB | 1.42 TB | 8.2 TB | Bills and audit now dominate. Option A upper bound was 5.4 / 9.3 / 54 TB |
| DB peak IOPS (option C, Phase 2) | 1,070 | 1,850 | 10,700 | One large instance at 10x; no sharding (data-architecture.md §8). Option A upper bound was 2,300 / 4,000 / 23,000 |
| Kafka peak | ~300 msg/s | ~520 msg/s | ~3,000 msg/s | Tiny; partitions sized for parallelism |
| Redis | 4 GB | 7 GB | 37 GB | |
| Pods (chat / worker) | 3 / 2 | 3 / 3 | 7 / 15 | |
