# Assumptions Register

Every number or interpretation in the Phase 1 documents that is not a verified fact is
listed here with an ID. Other documents reference these IDs (for example "per A-10").
All values are **ASSUMPTIONS** to be validated by the named owner. None of them is a fact
about a real operator.

- **Status:** `Proposed` = not yet reviewed; `Validated` = confirmed by the owner;
  `Replaced` = superseded (keep the row, strike the value, point to the new ID).
- **Config key:** where the value becomes a runtime or model parameter, the proposed
  property name. Nothing here is hardcoded in the application.

Last updated: 2026-09-25 (Phase 3a: A-71 to A-83 added in §8; A-72 extended by Q-20; note on A-64. Phase 2: A-58 to A-70 in §7; notes on A-17 and A-54).

---

## 1. Volume and traffic

| ID | Assumption | Value | Rationale / source | Validate with | Status |
|---|---|---|---|---|---|
| A-01 | Postpaid subscriber base | 10,000,000 | SPEC 1.4 | Business / BSS team | Proposed |
| A-02 | Bill-cycle dates per month; bills per cycle day | 5 dates; 10M ÷ 5 = **2,000,000 bills per cycle day** | SPEC 1.4; assumes even spread across cycles | Billing ops | Proposed |
| A-03 | Share of bills flagged anomalous | 4% → **80,000 proactive diagnoses per cycle day** | SPEC 1.4 | Billing ops (historical complaint data) | Proposed |
| A-04 | **Stress scenario only** (not the design peak): live chat | 3,000 concurrent sessions, 40 chat turns/s, 3–8 tool calls per turn | SPEC 1.4. Kept as the stress-test target. The design peak is derived from A-09 to A-11 instead (see capacity-estimates §3) | Care operations | Proposed |
| A-05 | Subscriber growth | 20% YoY → 1.2³ = **1.728x after 3 years** | SPEC 1.4 | Business planning | Proposed |
| A-06 | Headroom test target | **10x the derived 1x design peak** (chat and proactive), not 10x subscribers | Interpretation of SPEC 1.4 "10x headroom" | Architecture review | Proposed |
| A-07 | History kept per subscriber | 6 months (plus the current month, so 7 monthly partitions) | SPEC 1.4 | Product / legal (retention) | Proposed |
| A-08 | Bill-run window per cycle day; event burst | Bills for a cycle day are produced over **6 h**; peak event rate = **3x** the average | Typical batch billing; burst allows for uneven run output | Billing ops | Proposed |
| A-09 | Chat demand | Both rates are **per bill in one cycle-day's bill population (2,000,000 bills), per day**. On each of the **2 days after a cycle**: 1% × 2M = **20,000 conversations/day**. This figure *includes* background traffic from other cycles, so the two are not added. On the **other 20 days**: 0.3% × 2M = **6,000 conversations/day**, which is **0.06% of all 10M subscribers per day**. **6 turns** per conversation. 30-day month → 10 × 20,000 + 20 × 6,000 = **320,000 conversations/month** | Not from data; placeholder for care contact rates | Care operations (contact reason codes) | Proposed |
| A-10 | Peak-hour share of a peak day's chat turns | **15%** of daily turns fall in the busiest hour | New ID, added per review correction. Typical diurnal peak for digital care | Care operations (hourly contact curve) | Proposed |
| A-11 | Peak-minute burst within the peak hour | **1.5x** the peak-hour average rate | Provider rate limits are per minute (token bucket), so we size for bursts | Measured after launch | Proposed |
| A-12 | Average chat conversation duration (wall clock) | 8 min | Used only for concurrent-session estimates (Little's law) | Care operations | Proposed |
| A-13 | Average SSE stream duration per turn | 10 s | Consistent with the NFR p95 < 15 s for a full response | Gatling (Phase 10) | Proposed |

## 2. LLM usage profile

| ID | Assumption | Value | Rationale / source | Validate with | Status |
|---|---|---|---|---|---|
| A-14 | LLM round trips per chat turn | **Average 3**, p95 5, max 9 (the guardrail caps tool calls at 8, SPEC 4.5) | Parallel tool calls batch several of the 3–8 tool calls per round | Stubbed-LLM traces, then real evals (Phase 5/10) | Proposed |
| A-15 | Tokens per chat LLM call (`claude-sonnet-5`) | **12,000 input** on average (range 6k–20k; grows with the conversation). **250 output**, including adaptive-thinking and `tool_use` tokens | System prompt (~1.5k) + 16 tool definitions (~2.5k) + history + tool results. Counted in Sonnet 5's own tokenizer (see A-37) | `count_tokens` on real prompts (Phase 5) | Proposed |
| A-16 | Prompt-cache profile for chat input tokens | **75% cache read / 20% cache write (5-min TTL) / 5% uncached** | Multi-turn tool loops re-send a stable prefix. Effective input price multiplier = 0.75×0.1 + 0.20×1.25 + 0.05×1.0 = **0.375**. Only writes + uncached (25%) count toward ITPM | `usage.cache_read_input_tokens` in Phase 5 | Proposed |
| A-17 | Proactive diagnosis LLM usage (current Haiku-tier model (config); figures sized on `claude-haiku-4-5-20251001`) | **1 call** per flagged bill. 6,000 input, of which 2,000 is a cacheable static prefix; 600 output. **Phase 2 note (T-7):** Haiku 4.5's minimum cacheable prefix is **4,096 tokens** (Anthropic prompt-caching docs, 2026-09-25), so a 2,000-token prefix would not be cached. **Decided (Q-18):** no artificial padding; the prefix grows only with content that improves quality, otherwise accept +$720/month at 1x. Revisit with the successor Haiku-tier model's cache minimum | The worker passes the deterministic diagnosis; the LLM only writes the explanation and structured output (no tool loop) | Phase 7 | Proposed |
| A-18 | Proactive LLM call latency | 8 s average | Used to size the worker semaphore | Phase 7 measurement | Proposed |
| A-19 | Chat LLM call latency | 3 s average per round trip | Used for latency budget | Phase 5 measurement | Proposed |
| A-20 | LLM prices (USD per million tokens) | See §5 table | Anthropic pricing page, retrieved 2026-09-25 | Re-check before each budget review; enterprise discounts may apply | Proposed |
| A-21 | Rate-limit planning baseline | **Scale tier** standard limits (see §5). Limits above Scale are **custom and negotiable** | Anthropic rate-limits page, retrieved 2026-09-25 | Anthropic account team | Proposed |
| A-50 | **Haiku-first routing split** (cost scenario only, not the baseline) | **40%** of chat turns are simple/follow-up turns (clarification, confirmation, a question on an already-computed result, small talk) routed to the current Haiku-tier model (config). The other 60% (investigation and recommendation turns, always including the first turn) stay on `claude-sonnet-5`. Sensitivity 20% / 40% / 60% | New ID, added per Phase 1 review. Not measured; the real split comes from routed traffic in Phase 5 | Phase 5 traffic analysis + evals | Proposed |
| A-51 | Haiku follow-up turn profile | 1.5 LLM round trips; 12,000 input tokens per call (same conversation context); 200 output tokens per call | Follow-ups rarely need new tools | Phase 5 | Proposed |
| A-52 | Haiku turn cache profile | 50% cache read / 45% cache write / 5% uncached → effective input multiplier 0.6625. Worse than Sonnet's (A-16) because prompt caches are model-scoped: the first Haiku turn in a conversation writes the whole prefix again | Claude API prompt-caching docs: caches are per model | Phase 5 `usage` fields | Proposed |
| A-22 | Diagnosis-cache reuse by chat | 60% of chats about flagged bills find a cached diagnosis | Affects BSS gateway and DB load only. LLM tokens are unchanged because the tool result is the same size | Phase 7 cache hit metrics | Proposed |

## 3. Data and storage

| ID | Assumption | Value | Rationale / source | Validate with | Status |
|---|---|---|---|---|---|
| A-23 | Local usage storage (hybrid, part 1) | ~~**Daily rated aggregates** for all subscribers: **10 rows/subscriber/day** (range 8–15), keyed by usage type and roaming country. ~**230 bytes/row** including indexes and ~20% bloat~~ → **Replaced by A-56** (decision Q-6). Still used as "option A" in capacity-estimates §5.4 | Decision Q3: aggregates are stored locally for everyone | BSS usage team (feed format) | Replaced |
| A-53 | Roaming prevalence | 2% of subscribers roam in a month, 7 roaming days on average, 1.2 countries per trip | Used to size the narrow roaming-by-country tables | BSS / roaming team | Proposed |
| A-54 | Wide-row sizes (incl. indexes and bloat) | Daily wide row (subscriber-day, one column group per usage type) ~250 B; bill-period wide row ~300 B; roaming-by-country row ~200 B. **Phase 2 note:** synthetic-data measurement gave ~148 B (daily) and ~147 B (period) including the PK index (data-architecture.md §7.2), so these values look conservative by ~40–50%. Planning keeps them until real rows are measured. **Phase 3a note:** `pg_column_size` on the seed (row data only, excluding the ~28 B tuple header and line pointer and the indexes): `usage_daily` 99 B, `usage_period` 97 B, `usage_daily_roaming` 81 B, `usage_period_roaming` 70 B. This is consistent with the Phase 2 measurement; the planning values stay conservative. | Rule of thumb for ~12 numeric columns | Phase 3 schema (`pg_column_size`) | Proposed |
| A-55 | Detail requests for periods older than the previous cycle | 10% of `getUsageDetails` calls; always served from BSS on demand | Most questions are about the latest bill | Phase 5 tool stats | Proposed |
| A-57 | Late roaming usage | Roaming usage arrives through TAP files **days to weeks** after the trip. Late records are billed as **prior-period charges on the next bill**, which is a bill-shock cause in its own right (PRD §1, scenario 7) | Common inter-operator roaming settlement practice; the exact delays and billing treatment are operator-specific | BSS / roaming team | Proposed |
| A-56 | **Usage storage layout (decision Q-6, option C)** | (1) **Bill-period aggregates** per subscriber for 6 months + current: one wide row per subscriber-period plus a narrow roaming-by-country table. (2) **Daily** wide rows (+ daily roaming by country) only for the **current and previous cycle**. (3) **Per-session detail** from BSS on demand (A-25) | Consumer-to-granularity mapping in capacity-estimates §5.4 | Phase 2 data architecture | Proposed (decided 2026-09-25) |
| A-24 | Usage aggregate feed timing | Daily nightly feed, loaded over a **4 h** window | Assumed BSS export pattern | BSS team | Proposed |
| A-25 | Detailed usage (hybrid, part 2) | Fetched from BSS (TMF635) **on demand** only for accounts under investigation. Cached in Redis for the conversation (TTL 2 h), **not persisted** in PostgreSQL. ~2,000 records × 200 bytes per account-period. Needed in **50%** of conversations | Decision Q3 | Phase 5 tool-call stats | Proposed |
| A-26 | Bill and line-item size | ~15 line items per bill at ~200 B/row; ~500 B per bill header row. **Phase 3a note:** `pg_column_size` on the seed (row data only): `bill_line_item` 133 B, `bill` 106 B, below the planning values. | Typical consumer postpaid bill incl. GST lines | Billing ops | Proposed |
| A-27 | Audit volume | 10 audit rows per chat turn; 5 per proactive diagnosis; ~800 B/row (masked JSONB payload + indexes) | SPEC 4.3: audit every tool call and action | Phase 6 | Proposed |
| A-28 | Chat memory volume | 4 stored messages per turn × 1.5 KB | User, assistant, tool-call and tool-result records | Phase 5 | Proposed |
| A-29 | Retention periods (all **FOR LEGAL REVIEW**) | Usage aggregates: 7 monthly partitions, then dropped (BSS remains the system of record). Bills/line items: 7 months hot. Audit: 13 months hot, then archive to S3, total 7 years. Chat messages: 90 days hot, then transcript archive to S3 for 1 year | Placeholders chosen to show storage math. **Not** a legal interpretation | Legal / DPO | Proposed |
| A-48 | Diagnosis cache entry (Redis L2, per `bill_id`) | ~5 KB per entry; TTL 35 days (until the next bill plus margin); invalidated on bill adjustment | SPEC 2.3 does not set a TTL for the diagnosis cache | Phase 7 | Proposed |
| A-49 | Other tables (diagnoses, proposed actions, notifications, outbox, feedback, policy embeddings) | 50 GB lump-sum allowance at 1x | Small next to usage and audit | Phase 3 schema | Proposed |
| A-30 | DB I/O cost factors | Inserts (batched, key-sorted): **0.2 IOPS per row** amortised (heap + indexes + WAL). Clustered range reads: ~1 page per 35 rows, cold-cache worst case | Rule-of-thumb planning factors | Phase 2 EXPLAIN + Phase 10 load tests | Proposed |

## 4. Runtime sizing

| ID | Assumption | Value | Rationale / source | Validate with | Status |
|---|---|---|---|---|---|
| A-31 | `chat-api` pod capacity | ~500 concurrent SSE streams and ~**20 turns/s** CPU-bound throughput per pod (2 vCPU / 4 GiB, virtual threads). HPA targets 60% utilisation; **minimum 3 replicas** for 99.9% | I/O-bound workload; to be proven with Gatling | Phase 10 | Proposed |
| A-32 | `proactive-worker` pod capacity | LLM semaphore of **20** concurrent calls per pod; minimum 1 replica | SPEC 2.3 backpressure | Phase 7 | Proposed |
| A-33 | Non-production environments | dev + qa + staging cost **50% of 1x prod infra**, fixed (not scaled with load). Staging mirrors prod topology at a smaller size | Common practice | Finance / SRE | Proposed |
| A-34 | Infra unit prices | **INDICATIVE and UNVERIFIED** AWS ap-south-1 on-demand figures (see plan-and-budget §3). They were not checked against the AWS price list on 2026-09-25 | Replace with AWS Pricing Calculator output | SRE / Finance | Proposed |

## 5. Money

| ID | Assumption | Value | Rationale / source | Validate with | Status |
|---|---|---|---|---|---|
| A-35 | Cost of a human-handled care contact (fully loaded) | **Placeholder, three values: Low ₹50 / Mid ₹100 / High ₹200** per contact. Config key `billshock.cost.human-contact-inr` | No verified figure (decision Q4). Shown as a sensitivity table | Care operations finance | Proposed |
| A-36 | FX rate for reporting | **₹88 per USD** (planning rate, not a market quote). Config key `billshock.cost.fx-usd-inr` | Budgets are reported in INR; LLM is billed in USD (decision Q4) | Finance | Proposed |
| A-37 | Tokenizer | Estimates are in each model's own tokens. Anthropic states that Claude 4.7-and-later models use a newer tokenizer producing ~30% more tokens for the same text. Treat token counts from older benchmarks accordingly | Anthropic pricing page, 2026-09-25 | `count_tokens` (Phase 5) | Proposed |
| A-38 | Containment (resolved without a human) for the savings model | Placeholder: **40%** at autonomy Level 1; sensitivity 20% / 40% / 60% | No baseline exists (see A-43) | Care operations after pilot | Proposed |
| A-39 | Notification channel cost | In-app / push, zero marginal cost in the model. SMS/e-mail costs are out of scope | Simplification | Product | Proposed |

### LLM price table (A-20)

Source: <https://platform.claude.com/docs/en/about-claude/pricing>, retrieved **2026-09-25**.
Anthropic first-party API, global routing, USD per million tokens (MTok). All values are
config parameters (`billshock.llm.pricing.<model>.*`), never hardcoded.

| Model (config id) | Input | 5-min cache write | 1-h cache write | Cache read | Output | Batch input | Batch output |
|---|---|---|---|---|---|---|---|
| `claude-sonnet-5` (live chat) | $2.00 | $2.50 | $4.00 | $0.20 | $10.00 | $1.00 | $5.00 |
| `claude-haiku-4-5-20251001` (proactive; the current Haiku-tier model (config) at the time of writing) | $1.00 | $1.25 | $2.00 | $0.10 | $5.00 | $0.50 | $2.50 |

Notes from the same page (2026-09-25):
- The $2/$10 Sonnet 5 price was introductory until 2026-08-31 and is now standard. The
  previously scheduled rise to $3/$15 will not happen.
- The Batch API gives 50% off input and output. Caching multipliers stack with batch and
  data-residency modifiers.
- `inference_geo: "us"` adds a 1.1x multiplier. Only `global` (default) and `us` are listed
  for first-party inference; see risk R-05 (data residency).
- Bedrock and Vertex AI have separate pricing. Their regional endpoints carry a 10% premium
  over global endpoints.
- Volume discounts and enterprise pricing are negotiated case by case.

### Rate-limit table (A-21)

Source: <https://platform.claude.com/docs/en/api/rate-limits>, retrieved **2026-09-25**.
Limits are per organisation and per model class. Standard tiers:

| Tier | Monthly spend cap | Sonnet 5 RPM / ITPM / OTPM | Haiku 4.5 RPM / ITPM / OTPM | Batch API RPM / queue |
|---|---|---|---|---|
| Start | $500 | 1,000 / 2,000,000 / 400,000 | 1,000 / 2,000,000 / 400,000 | 1,000 / 200,000 |
| Build | $1,000 | 5,000 / 5,000,000 / 1,000,000 | 5,000 / 5,000,000 / 1,000,000 | 2,000 / 300,000 |
| Scale | $200,000 | 10,000 / 10,000,000 / 2,000,000 | 10,000 / 10,000,000 / 2,000,000 | 4,000 / 500,000 |
| Custom | None (arranged with account team) | **Negotiable** | **Negotiable** | **Negotiable** |

Relevant rules from the same page:
- For these models, **cache-read tokens do not count toward ITPM**. Only uncached input and
  cache writes count.
- Limits are enforced with a token bucket and can bite on sub-minute bursts. Sharp ramps
  can trigger "acceleration limits".
- Sonnet 5 and Haiku 4.5 have **separate** limits, so proactive batch traffic cannot use
  up the live-chat model's quota. Both still share the organisation's spend cap.
- Workspace-level limits can be set below the organisation limit. This is how we
  implement SPEC 2.3 "separate quotas for live chat and batch".

## 6. Product, team and governance

| ID | Assumption | Value | Rationale / source | Validate with | Status |
|---|---|---|---|---|---|
| A-40 | Care-agent assisted mode | The care agent uses the same tools, scoped to the customer they are serving. They may confirm an action for the customer only with a recorded verbal-consent flag | Keeps the HITL rule (SPEC 4.3) | Care operations / legal | Proposed |
| A-41 | Launch autonomy level | Level 0 (explain only), moving up after metric review | SPEC 8.2 | Product / risk | Proposed |
| A-42 | Regulatory content | Obligations under DPDP Act 2023 and Indian telecom consumer-protection rules are listed as risks, marked **FOR LEGAL REVIEW**. No compliance is claimed | SPEC 2.5 and review correction 3 | Legal | Proposed |
| A-43 | KPI baselines | Current AHT, dispute rate, CSAT and repeat-contact rate are **unknown**, recorded as `TBD-baseline`. Targets are placeholders | No data available to this project | Care operations / BI | Proposed |
| A-44 | Cross-channel contact data | A contact-history feed (CRM / IVR / store) is available by bill period, so "repeat contact within 7 days" can be measured across channels | Needed for KPI K-06 | CRM team | Proposed |
| A-45 | Enterprise team and start | 1 EM, 4 backend engineers, 1 QA, 0.5 SRE, 0.25 product owner. Start **2026-10-01** (W0). Dates are week offsets | Decision Q5 | Engineering management | Proposed |
| A-46 | Reference implementation | Built by **one engineer** as a portfolio project, with AI coding assistance, full-time equivalent. Real AWS drills are replaced by `terraform plan` and local simulations | Decision Q5 | Repo owner | Proposed |
| A-47 | Eval and CI LLM spend | $500/month (staging eval gate plus prompt experiments) | Placeholder | Phase 10 | Proposed |

## 7. Phase 2 additions (architecture and data)

A-58 to A-65 were proposed at the start of Phase 2 and **accepted by the repo owner on
2026-09-25**. A-66 to A-70 were introduced while writing the Phase 2 documents.

| ID | Assumption | Value | Rationale / source | Validate with | Status |
|---|---|---|---|---|---|
| A-58 | Cloud regions | Primary **AWS ap-south-1 (Mumbai)**; DR **ap-south-2 (Hyderabad)**, so backups and snapshot copies stay in India | SPEC §3 reference target; keeps all non-LLM data in-country | SRE / legal | Validated (owner, 2026-09-25) |
| A-59 | Packaging | **One Maven artifact and one image.** `chat-api` and `proactive-worker` are selected by Spring profile | ADR-001 | Architecture review | Validated (owner, 2026-09-25) |
| A-60 | Daily usage partitions | `usage_daily` partitioned **monthly by `billed_period`**, **3 partitions** kept (~0.23 TB upper bound at 1x) | ADR-007; simpler than weekly; matches the retention job | Phase 3 | Validated (owner, 2026-09-25) |
| A-61 | Tariff features | The v1 catalogue has **no time-of-day tariffs**, so no hourly buckets | capacity §5.4 open point | Product / catalogue team | Validated (owner, 2026-09-25) |
| A-62 | Extra topic | `bill.adjusted` for cache invalidation and re-roll-up; DLQs named `<topic>.dlq` | SPEC §2.3 needs an adjustment event | BSS integration team | Validated (owner, 2026-09-25) |
| A-63 | Usage feed | The BSS usage feed is a **nightly batch pull** (A-24), not Kafka. **Limitation:** local usage is at best up to the previous day, so **in-trip real-time alerts are not possible in v1**. Real-time usage events are a future enhancement (architecture.md §10) | Assumed BSS export pattern | BSS team | Validated (owner, 2026-09-25) |
| A-64 | Chat memory store | Custom `ChatMemoryRepository` over the monthly-partitioned `chat_messages`. **Confirmed in Phase 2:** Spring AI 1.1.8's `JdbcChatMemoryRepository` uses a fixed table and deletes and re-inserts the whole conversation on every save. **Re-confirmed in Phase 3a** for the pinned 2.0.1 (ADR-008) | llm-architecture.md F-4 | Phase 5a | Validated (owner, 2026-09-25) |
| A-65 | Diagrams | Mermaid flowcharts in C4 style instead of Mermaid's experimental C4 syntax | Rendering reliability (all 10 diagrams checked with Mermaid 11) | — | Validated (owner, 2026-09-25) |
| A-66 | Usage period keys from BSS | The daily feed and TAP-derived late batches carry, per record, the **billed period** (or cycle id) and the **original usage date**. Late line items identify the original usage period. If the billed period is missing, it is derived from the account's cycle day | ADR-007 §8 | BSS / roaming team | Proposed |
| A-67 | Lines per account | **One postpaid line (MSISDN) per account** in v1; multi-line accounts would add `line_id` to the usage keys | Enterprise/family accounts out of scope (SPEC §1.2) | Product | Proposed |
| A-68 | BSS connectivity | BSS APIs reachable over **private connectivity** from the VPC, with mTLS or OAuth2 client credentials | security.md §1 | BSS integration team | Proposed |
| A-69 | Identity providers | Production IdPs issue OIDC JWTs with the customer's account binding, and support token exchange for care-agent assisted sessions | security.md §2 | IAM team | Proposed |
| A-70 | Cross-region DB recovery | RDS PostgreSQL cross-region automated backup replication (snapshots + transaction logs) to ap-south-2 meets **RPO ≤ 15 min** for a region loss; otherwise a cross-region read replica is needed | data-architecture.md §12. **Not verified** against AWS docs in Phase 2 | Phase 11 (AWS docs) + Phase 12 DR drill | Proposed |

## 8. Phase 3a additions (seed data, GST, set-up)

Proposed in [seed-scenarios.md](../03-development/seed-scenarios.md) (owner review
pending). The seed SQL is written only after that review.

| ID | Assumption | Value | Rationale / source | Validate with | Status |
|---|---|---|---|---|---|
| A-71 | Seed bill-cycle days | 1, 6, 11, 16, 21 | A-02 says 5 cycle dates; the actual days are unknown | Billing ops | Proposed |
| A-72 | GST model | Tax on the **bill-level** taxable value; **CGST 9% + SGST 9%** intra-state, **IGST 18%** inter-state (by the account's state); each component rounded **HALF_EVEN** to 2 dp; place of supply = billing-address state for postpaid mobile. Issued bills are never recomputed; the rule is used for consistency checks, simulations and refund GST. **Decision Q-20:** credits and refunds include GST, and goodwill thresholds apply to the GST-inclusive amount. Config: `billshock.tax.gst-rate=18.00` | Owner instruction (Phase 3a, addition B); Indian telecom invoice practice | **Tax / finance (FOR TAX REVIEW)** | Proposed |
| A-73 | Supplier GST registrations | **Config list** `billshock.tax.supplier-state-codes`: the GST state codes in which the operator holds a registration. A bill is intra-state (CGST + SGST) when its place of supply is in the list, otherwise inter-state (IGST). **Seed: `[27]` (Maharashtra) only**, deliberately, so that 3 of the 6 seed accounts stay inter-state and the IGST path keeps test coverage. **Note (gate review, 2026-09-25):** large Indian telcos typically hold a GST registration in each state they operate in, so in production most postpaid bills would be intra-state; the production list would contain every state served | Owner gate review; seed-scenarios.md §2 | **Tax / finance (FOR TAX REVIEW)** | Proposed |
| A-74 | Seed tariff shape | Unlimited domestic voice; SMS 3,000/month then ₹1.00; data overage ₹0.02/MB (1 GB = 1,024 MB); ISD ₹6.00/min | Exact paise arithmetic; simple enough to verify by hand | Product / catalogue team | Proposed |
| A-75 | Seed roaming rates | Pay per use, the same on every plan; one voice rate for incoming and outgoing (GCC ₹2.00/MB, ₹60.00/min, ₹25.00/SMS) | The usage layout has one `roaming_voice_min` column | Product / catalogue team | Proposed |
| A-76 | Seed MSISDNs | `+91 5…` range, outside India's 6–9 mobile series, so not a real subscriber | Avoids real numbers in fixtures | — | Proposed |
| A-77 | Seed id scheme | `bill_id = account_id × 10000 + YYMM`; `line_item_id = bill_id × 100 + seq` | Readable ids in tests and the demo | — | Proposed |
| A-78 | Seed credit history | No seed account has a prior goodwill credit | Keeps the MVP scenarios focused; guardrail history cases are unit-tested in 4a | — | Proposed |
| A-79 | Proration rule | Rental × days on the plan ÷ days in the usage period, HALF_EVEN per line, billed in the period of use | Common operator practice; the real rule comes from BSS | Billing ops | Proposed |
| A-80 | Mock BSS data | Subscriptions, opt-in evidence and orders come from JSON fixtures served by in-process mock gateways, not from tables | data-architecture.md §1: these are not stored locally | — | Proposed |
| A-81 | Seed daily rows | Monthly quantities split evenly in whole units, remainder on the last day; overage attributed in date order after the allowance is used; other domestic charges (ISD) on the last day | Deterministic; Σ daily = period row | — | Proposed |
| A-82 | Phase 3a set-up | Flyway runs at startup only in the `dev` and `test` profiles (a pre-deploy Kubernetes Job elsewhere, Phase 11); seed migrations in `db/seed`, enabled only in those profiles; 3a imports only the Spring AI BOM (starters come in 5a, so no API key is needed to build); `./mvnw verify` needs Docker for Testcontainers | SPEC §6 (migrations as a Job); AGENTS.md (no secrets) | Owner | Proposed |
| A-83 | Bill-change thresholds | **Chat "meaningful increase"** (Q-22): excess ≥ ₹100 and ≥ 10% of the baseline (config `billshock.analysis.chat.min-excess-inr`, `…min-excess-pct`). **Proactive `AnomalyDetector`**: ratio ≥ 1.8x baseline or z > 2.5 (config `billshock.anomaly.ratio-threshold`, `…z-threshold`, `…z-history-bills=6`). Both use GST-inclusive totals; baseline = mean of 3 prior bills; z over 6 prior bills, sample σ; **Q-24:** σ below `billshock.anomaly.flat-sigma-floor-inr=1.00` counts as a flat history, so z is not applied and only the ratio decides. 4b also adds rule-based checks (Q-25): a new third-party VAS without double opt-in (anomaly alert) and the first bill after a plan change (informational notice). The two rules are separate and never share config | Owner decision Q-22 and the owner's proactive rule (Phase 3a); seed-scenarios.md §5.7 | Care operations (chat); marketing/CX (proactive volume vs the 4% planning figure) | Proposed |
