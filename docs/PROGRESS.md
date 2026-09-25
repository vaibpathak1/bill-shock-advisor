# Progress Log

| Phase | Status | Notes |
|---|---|---|
| 1. Docs I | **Done** (approved 2026-09-25) | PRD, feasibility, capacity estimates, plan and budget, NFRs, assumptions register |
| 2. Docs II | **Done; review answers Q-14–Q-19 applied** (2026-09-25); waiting for the go-ahead for 3a | Architecture, ADR-001 to ADR-007, data architecture (with EXPLAIN evidence), scalability, security, LLM architecture, observability |
| 3a. Foundation (MVP slice) | Not started | Scope: plan-and-budget §2a |
| 4a. Deterministic core (MVP slice) | Not started | |
| 5a. Agent (MVP slice) | Not started | |
| 6a. Actions (MVP slice) + demo wrap-up | Not started | All 6 scenarios end to end |
| 3b. Foundation (remainder) | Not started | |
| 4b. Deterministic core (remainder) | Not started | |
| 5b. Agent (remainder) | Not started | Haiku-first routing decision (Q-13) |
| 6b. Actions (remainder) | Not started | |
| 7. Async and scale | Not started | |
| 8. RAG and security | Not started | |
| 9. Observability | Not started | |
| 10. Testing depth | Not started | |
| 11. Deploy | Not started | |
| 12. Ops docs | Not started | |

## Phase 1 summary (2026-09-25)

Files (uncommitted, for review):
- `docs/01-requirements/assumptions.md`: shared register, A-01 to A-49, with cited
  Anthropic prices and rate limits (retrieved 2026-09-25)
- `docs/01-requirements/PRD.md`: personas, user stories with acceptance criteria for all
  5 capabilities, KPIs K-01 to K-07, scope, autonomy release plan
- `docs/01-requirements/feasibility.md`: technical and financial feasibility,
  human-cost sensitivity, risk register R-01 to R-20
- `docs/01-requirements/capacity-estimates.md`: 1x / 3-yr / 10x / stress derivations
- `docs/01-requirements/plan-and-budget.md`: enterprise and solo timelines, run cost at
  1x / 3x / 10x, cost controls
- `docs/02-design/nfr.md`: SPEC NFRs with SLIs, derived throughput targets, proposed extra
  NFRs

Headline results:
- Chat design peak: 5 turns/s at the peak hour, 7.5 turns/s peak minute (1x). 40 turns/s
  kept as the stress scenario only.
- With prompt caching, 1x uses 41% of Sonnet 5 Scale-tier ITPM. **Caching is required**;
  without it, 1x does not fit. Custom limits are needed around year 3, at 10x, and for
  stress testing against the real provider.
- Run cost at 1x ≈ ₹78 lakh/month, of which LLM ≈ 79% (**Phase 2:** ₹75.6 lakh, LLM 81%, after
  re-deriving the DB with usage option C). Spend passes the Scale tier's
  $200k/month cap at ~2.9x load.
- Unit cost ≈ ₹21 per chat conversation and ₹0.63 per proactive diagnosis. Break-even
  containment is 42% / 21% / 10.5% at a human contact cost of ₹50 / ₹100 / ₹200
  (**Phase 2:** ₹20.6 and 41% / 21% / 10.3%).

## Phase 2 summary (2026-09-25)

Files (uncommitted, for review; the owner commits, per K-Q8):
- New: `docs/02-design/architecture.md` (C4 context and container, chat and proactive
  sequences, deployment, module map, event catalogue, traceability, future enhancements)
- New: `docs/02-design/adr/001` to `007` (ADR-005 **pending owner**; ADR-007 new, per
  answer 6)
- New: `docs/02-design/data-architecture.md` (ER diagram, table list, partitioning by
  period, read/write routing with Q-8, indexes, **EXPLAIN evidence in Appendix A**,
  sharding path and triggers, option C re-derivation, retention, S3/CDN, backup/DR and
  restore procedure, PII data flow)
- New: `docs/02-design/scalability.md`, `security.md`, `llm-architecture.md`,
  `observability.md`
- Updated: `SPEC.md` §2.2 (usage layout option C; answer 5);
  `docs/01-requirements/assumptions.md` (A-58 to A-70; notes on A-17 and A-54);
  `capacity-estimates.md` (option C figures in §5.3, §5.4, §6, §10);
  `plan-and-budget.md` (DB lines and totals); `feasibility.md` (unit cost, T-6 to T-8,
  R-05 result, new R-21); `nfr.md` (NFR-01b measurement point)

Headline results:
- **Data residency (Q-10):** official Bedrock and Vertex AI docs (retrieved 2026-09-25) show
  **no India region** for Claude Sonnet 5 or Haiku 4.5. Bedrock offers only the *global*
  profile from ap-south-1/2 (no in-region, no India/APAC geo profile); Vertex offers
  US/EU/global. ADR-005 recommends the Anthropic global endpoint + strict minimisation +
  template-only fall-back, subject to legal review. **Owner to decide.**
- **Temperature (Q-1, documented behaviour):** Anthropic deprecated `temperature`/`top_p`/`top_k`
  for 4.7-and-later models (400 on a non-default value), so it is omitted for
  `claude-sonnet-5`. **Spring AI 1.1.8 sends temperature 0.8 by default**, so both ChatModel
  beans are built explicitly. Live check in 5a.
- **Spring AI 1.1.8** (latest 1.x; checked in sources): prompt caching is supported
  (`AnthropicCacheOptions`, strategies including `CONVERSATION_HISTORY`), which looks good
  for Q-2. There is **no `effort` option** (T-6). The built-in JDBC chat memory deletes and
  re-inserts whole conversations, which confirms the custom repository (A-64).
- **Haiku 4.5 needs ≥ 4,096 tokens to cache** (T-7): the proactive prefix must be designed
  to that size, or it costs +$720/month at 1x.
- **Late usage (answer 4):** every usage row has `billed_period` + `usage_period`; all usage
  tables are **partitioned by `billed_period`**, so late records always land in the open
  partition; ingest is idempotent per `batch_id` (ledger + replace-by-batch); roll-up is
  a recompute; records older than the daily retention live only as period rows (ADR-007
  §7).
- **EXPLAIN (answer 3):** 200,000 accounts (1/50 of 1x), 21 M line items, 15 M daily rows,
  6.3 GB database (12x `shared_buffers`). All top-5 queries use index scans with partition
  pruning, 0.04–7.8 ms cold; a generic prepared statement shows runtime pruning.
- **Capacity with option C:** primary DB ≈ 0.82 TB and ≈ 1,070 peak IOPS at 1x; ≈ 8.2 TB and
  ≈ 10,700 IOPS at 10x, so **no sharding needed at 10x**. Run cost at 1x ≈ **₹75.6 lakh/month**
  (was ₹78.0).
- **Correction found in Phase 1's budget:** the 3x infra total was printed as $27,075, but
  its rows add up to $29,075 (Phase 1's 3x run cost should have been ₹214.1 lakh, not
  ₹212.3). The Phase 2 figures are computed from the rows: 3x infra $23,625, total
  ₹208.5 lakh/month.
- **New risk R-21:** Haiku 4.5 retirement is "not sooner than" 15 Oct 2026 (Claude API and
  Vertex) and 1 Oct 2026 (Bedrock).

## Open questions

All questions up to Q-19 are decided; see the log below. Only follow-up actions remain.

| # | Status | Remaining action | When |
|---|---|---|---|
| Q-1 | Decided; live check deferred | Documented behaviour used (llm-architecture.md §3). Live call to `claude-sonnet-5` with and without temperature, **only with the owner's approval** | Phase 5a |
| Q-2 | Decided | Spring AI 1.1.8 sources show caching support (llm-architecture.md F-1). Re-check in the version actually pinned; **stop and tell the owner if unsupported** | Phase 3a, before pinning |
| Q-3 | Decided (with Q-7) | — | — |
| Q-4 | Done | Both SLIs and the hourly alert are in `observability.md` §1, §6 | — |
| Q-5 | Done | Used in the NFR-04 SLI and alert (`observability.md`) and the KEDA sizing (`scalability.md` §3) | — |
| Q-6 | Done | ADR-007 + data-architecture.md §4.3, §5, §9; open points closed (columns, late CDRs, partition granularity A-60, time-of-day A-61) | — |
| Q-7 | Decided | — | — |
| Q-8 | Done | data-architecture.md §6 | — |
| Q-9 | Decided (defer) | Verify infra prices with the AWS Pricing Calculator before any real budget decision | Before a budget decision |
| Q-10 | Done: no India region found | Research in ADR-005 (retrieved 2026-09-25). The decision itself is Q-14 | — |
| Q-11 | Decided | — | — |
| Q-12 | Decided | — | — |
| Q-13 | Deferred by agreement | Measure the real turn mix, then decide on Haiku-first routing | Phase 5b |
| Q-14 | Decided | ADR-005 "recommended — pending legal review". Record the legal/DPO sign-off in ADR-005 before any production data | Before production |
| Q-15 | Decided | No fall-back model now. Choose the current Haiku-tier model in 5a using the eval-gated process (llm-architecture.md §15) | Phase 5a |
| Q-16 | Decided | Implement `clientMessageId` + `GET /api/v1/chat/{conversationId}/messages` as designed in architecture.md §11 | **Phase 5b** (not in the MVP slice) |
| Q-17 | Decided | Implement `NUMERIC(14,6)` USD metering with prices per MTok in config | Phase 5a |
| Q-18 | Decided | No artificial padding; extend the proactive prompt only with quality-improving content; revisit with the successor model's cache minimum | Phase 7 |
| Q-19 | Decided | — | — |

## Decisions log

K-Q1…K-Q8 are the kickoff questions answered before Phase 1 was written. Q-1…Q-13 are
the questions raised in the Phase 1 documents.

| Date | Decision | By |
|---|---|---|
| 2026-09-25 | K-Q1: NFR doc lives at `docs/02-design/nfr.md` and is written in Phase 1 | Repo owner |
| 2026-09-25 | K-Q2: Use Anthropic's published prices and rate-limit tiers, cited with source and date, as config parameters; show where custom (negotiated) limits are needed | Repo owner |
| 2026-09-25 | K-Q3: Hybrid usage storage: aggregates stored locally for all subscribers; per-session detail fetched from BSS on demand and cached per conversation (layout refined by Q-6) | Repo owner |
| 2026-09-25 | K-Q4: Human contact cost is a configurable placeholder with low/mid/high sensitivity; budgets in INR, with USD alongside for LLM | Repo owner |
| 2026-09-25 | K-Q5: Enterprise team plan (1 EM, 4 BE, 1 QA, 0.5 SRE, 0.25 PO) from 2026-10-01 in week offsets, plus a separate solo timeline | Repo owner |
| 2026-09-25 | K-Q6: Data residency flagged as an open risk; verify in Phase 2 for ADR-005 | Repo owner |
| 2026-09-25 | K-Q7: Shared assumptions register at `docs/01-requirements/assumptions.md` | Repo owner |
| 2026-09-25 | K-Q8: No commits by the agent; the owner reviews and commits | Repo owner |
| 2026-09-25 | Correction: chat design peak derived from A-09 plus the peak-hour share (A-10); 40 turns/s kept as the stress scenario only | Repo owner |
| 2026-09-25 | Correction: added KPIs for 7-day repeat contact (K-06) and proactive outreach acceptance (K-07) | Repo owner |
| 2026-09-25 | Correction: added Indian telecom consumer-protection rules on usage alerts and billing transparency to the risk register (R-09, FOR LEGAL REVIEW) | Repo owner |
| 2026-09-25 | Q-1: Temperature is a per-model config property, omitted for models that reject sampling parameters (e.g. `claude-sonnet-5`). Verify with a live call in Phase 2. SPEC 2.6 updated | Repo owner |
| 2026-09-25 | Q-2: Verify Anthropic prompt caching support before pinning the Spring AI version in Phase 3. If unsupported, stop and tell the owner, because the cost model depends on it. SPEC 2.6 updated | Repo owner |
| 2026-09-25 | Q-3 + Q-7: The orchestrator runs `diffBills` in code before the first LLM call. It streams a deterministic one-line spike summary (amount + top driver, via the fallback templates) within 1 s, then the LLM continues. NFRs: first meaningful content p95 < 1.5 s, first LLM-generated word p95 < 8 s. SPEC 2.4 and 4.6, nfr.md (NFR-01a/b), PRD US-INV-01 updated | Repo owner |
| 2026-09-25 | Review fix 1: LLM monthly total reconciled (320,000 × $0.207 + 400,000 × $0.0072 + $500 = $69,620). Chat rates in A-09 clarified as per bill in one cycle-day's population per day (off-peak 0.3% = 0.06% of subscribers per day). No numbers changed | Repo owner |
| 2026-09-25 | Review fix 2: Haiku-first routing cost scenario added (plan-and-budget §3.2a); the routing split is new assumption A-50 (plus A-51, A-52) | Repo owner |
| 2026-09-25 | Review fix 3: MVP demo slice added (plan-and-budget §2a): the smallest subset of Phases 3–6 demoing all 6 scenarios end to end; solo estimate ≈ 7 weeks; everything else after it | Repo owner |
| 2026-09-25 | Q-12: SPEC §11 updated. Execution order is 1 → 2 → MVP slice (3a → 4a → 5a → 6a) → remainder (3b → 4b → 5b → 6b) → 7–12. Phase gates apply to every sub-phase | Repo owner |
| 2026-09-25 | Q-13: Haiku-first routing decided in Phase 5 (5b) after measuring the real turn mix | Repo owner |
| 2026-09-25 | **Q-4:** Report two availability SLIs. **"Available"** counts a template-fallback answer as success; the 99.9% monthly target applies to it. **"Full capability"** counts only LLM answers. **Alert when full capability drops below 99.5% in any 1-hour window, even while "available" is green.** nfr.md NFR-05 updated | Repo owner |
| 2026-09-25 | **Q-5:** The 6 h proactive window starts at the **first `bill.generated` event of the cycle day**. nfr.md NFR-04 updated | Repo owner |
| 2026-09-25 | **Q-6:** Usage layout **option C** (A-56): bill-period aggregates for 6 months + current (wide row + narrow roaming-by-country table); daily rows only for the current and previous cycle; per-session detail from BSS on demand. No consumer needs 6 months of daily data. The one edge case, `simulatePlans` on an older period with day-based tariffs, uses BSS detail on demand and never approximates. Comparison: usage storage 4.83 TB (A) / 0.53 TB (B) / 0.17–0.25 TB (C); peak IOPS ~2,300 / ~880 / ~1,070 at 1x. Full analysis in capacity-estimates §5.4. Decided by the agent's recommendation on the owner's instruction; the owner may override at Phase 2 review. Option B is the fall-back | Agent recommendation, per owner |
| 2026-09-25 | **Q-8:** The proactive worker reads the **current bill and its diff from the primary**; older history from the read replica. Encode as a routing rule in the data architecture | Repo owner |
| 2026-09-25 | **Q-9:** Infra prices stay indicative (A-34); verify with the AWS Pricing Calculator only before a real budget decision | Repo owner |
| 2026-09-25 | **Q-10:** In Phase 2, research whether Claude is available in an India region using **only official Amazon Bedrock and Google Vertex AI documentation, cited with the retrieval date**. If an India region exists, ADR-005 uses it. If not, ADR-005 recommends the global endpoint with strict data minimisation (masked MSISDN, charges only), subject to legal assessment, with the template-only mode as a fall-back; the owner decides | Repo owner |
| 2026-09-25 | **Q-11:** Assumptions keep role-based validation owners. For this portfolio repo, the repo owner acts as owner; values stay "Proposed" with sensitivity tables shown | Repo owner |
| 2026-09-25 | Late roaming via TAP files: roll-ups must support idempotent recomputation; late records billed as prior-period charges on the next bill (A-57). New bill-shock cause added to the PRD; **7th seed scenario** scheduled for **6b** (not in the MVP slice) | Repo owner |
| 2026-09-25 | **Phase 1 approved**; Phase 2 started | Repo owner |
| 2026-09-25 | Phase 2 answer 2 (**Q-1**): option (c): defer the live temperature check to 5a; use documented behaviour, marked "verify in 5a" | Repo owner |
| 2026-09-25 | Phase 2 answer 3: real EXPLAIN plans on a throwaway Postgres 16 with a synthetic dataset large enough for realistic plans; row counts and table stats stated next to the plans; nothing in the repo | Repo owner |
| 2026-09-25 | Phase 2 answer 4: late usage with `usage_period` + `billed_period` and batch-id idempotency; the docs must state the partition key of each table and why, and what happens to late records older than the daily retention (ADR-007 §3, §7) | Repo owner |
| 2026-09-25 | Phase 2 answer 5: SPEC §2.2 updated to option C | Repo owner |
| 2026-09-25 | Phase 2 answer 6: ADR-007 added (usage layout, option C, late usage) | Repo owner |
| 2026-09-25 | **Q-14:** ADR-005 option A (Anthropic global endpoint) + strict minimisation + template-only fall-back is **"recommended — pending legal review"**. A rejected option was added: self-hosted open-weight model in an Indian region (tool-calling reliability, eval quality, ops burden, cost) | Repo owner |
| 2026-09-25 | **Q-15:** no fall-back model chosen now. Hardcoded Haiku model ids in SPEC and docs replaced with "current Haiku-tier model (config)" where the version isn't essential (kept in cited research, price/rate-limit tables, version-specific facts and R-21). Eval-gated model switch process documented (llm-architecture.md §15). The model is chosen in 5a | Repo owner |
| 2026-09-25 | **Q-16:** stream-recovery API (`clientMessageId`, `GET /api/v1/chat/{conversationId}/messages`) documented in architecture.md §11; implement in 5b, not in the MVP slice | Repo owner |
| 2026-09-25 | **Q-17:** `NUMERIC(14,6)` USD for LLM cost metering accepted; token prices stored per million tokens (llm-architecture.md §12) | Repo owner |
| 2026-09-25 | **Q-18:** no artificial prompt padding; extend the fixed proactive prompt only with content that improves quality; accept ~$720/month otherwise; revisit with the successor Haiku-tier model's cache minimum | Repo owner |
| 2026-09-25 | **Q-19:** updated figures accepted (₹75.6 lakh/month at 1x, ₹20.6 per conversation, corrected 3x total ₹208.5 lakh) | Repo owner |
| 2026-09-25 | Phase 2 answer 7: A-58 to A-65 accepted. A-63 limitation documented (usage up to the previous day; no in-trip real-time alerts in v1; real-time usage events as a future enhancement) | Repo owner |

## Notes for the Phase 3a session

- **Versions to pin (latest on Maven Central, 2026-09-25):** Spring Boot **3.5.16**, Spring AI
  **1.1.8** (BOM), Spring Modulith **1.4.13**. Re-check prompt caching (Q-2) in the sources of
  the version actually pinned before writing it into `pom.xml`.
- The schema follows `data-architecture.md` (partition keys, primary keys that include the
  partition key, indexes in §7.1). The throwaway DDL used for EXPLAIN is a starting point
  but is not the Flyway schema.
- **docker-compose:** give PostgreSQL `shm_size: 256mb` or more. Docker's default 64 MB
  `/dev/shm` broke parallel `VACUUM` in the EXPLAIN test.
- Measure real row sizes (`pg_column_size`) on seed data and compare with A-54 and A-26.
- 5a: choose the current Haiku-tier model via llm-architecture.md §15 (Q-15); implement
  cost metering with prices per MTok (Q-17). 5b: stream-recovery API (Q-16).
- 5a reminders: build the Anthropic ChatModel beans explicitly (no default temperature 0.8;
  max tokens set); keep customer data out of the system prompt; the `diffBills` pre-fetch
  goes in as a message; live temperature check only with owner approval.
- **7th seed scenario** (late roaming) stays scheduled for 6b; the data model supports it now
  (ADR-007).

## Next step

Phase 2 is complete and all review answers are applied (uncommitted; the owner commits).
Wait for the go-ahead to start Phase 3a (Foundation, MVP slice; scope in plan-and-budget §2a).
