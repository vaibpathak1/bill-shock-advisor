# Progress Log

| Phase | Status | Notes |
|---|---|---|
| 1. Docs I | **Done** (approved 2026-09-25) | PRD, feasibility, capacity estimates, plan and budget, NFRs, assumptions register |
| 2. Docs II | **Done; review answers Q-14–Q-19 applied** (2026-09-25) | Architecture, ADR-001 to ADR-007, data architecture (with EXPLAIN evidence), scalability, security, LLM architecture, observability |
| 3a. Foundation (MVP slice) | **Done, approved** (owner, 2026-09-25). Gate-review changes applied; `./mvnw verify` passes | Scope: plan-and-budget §2a |
| 4a. Deterministic core (MVP slice) | **Gate review changes applied** (2026-09-25); `./mvnw verify` passes (112 unit + 94 IT) | Design: `docs/03-development/deterministic-core.md` |
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

All questions up to Q-31 are decided; see the log below. Only follow-up actions remain, plus the 4a gate items in the Phase 4a section.

| # | Status | Remaining action | When |
|---|---|---|---|
| Q-1 | Decided; live check deferred | Documented behaviour used (llm-architecture.md §3). Live call to `claude-sonnet-5` with and without temperature, **only with the owner's approval** | Phase 5a |
| Q-2 | Done | Caching verified in the pinned Spring AI 2.0.1 sources (llm-architecture.md F-1, ADR-008) | — |
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
| Q-20 | Decided | Credits/refunds include GST; goodwill thresholds on the GST-inclusive amount; FOR TAX REVIEW (A-72) | Implement in 4a (guardrails) / 6a (actions) |
| Q-21 | Decided | Plans top 3 + best single add-on; no multiples | 4a |
| Q-22 | Decided | Chat threshold ≥ ₹100 and ≥ 10%, separate from the proactive rule (≥ 1.8x or z > 2.5); expectations in seed-scenarios.md §5.7 (A-83) | 4a (chat); detector per Q-26 |
| Q-23 | Decided | Per-cause excl. GST / GST / incl. GST; replies always state GST and name a cause by its GST-inclusive figure | 4a (engine), 5a (prompt, templates, grounding gate) |
| Q-24 | Decided | σ below a configurable floor (₹1.00) counts as a flat history; z is then not applied and only the ratio decides | 4b |
| Q-25 | Decided | 4b adds the rule "new third-party VAS without double opt-in" (anomaly alert) and the optional rule "first bill after a plan change" (informational notice explaining proration) | 4b |
| Q-26 | Decided | `AnomalyDetector` stays in 4b; 4a tests the chat column of seed-scenarios.md §5.7, 4b the proactive columns | 4a / 4b |
| Q-27 | Done (4a) | `RECENT_PLAN_CHANGE` rule built and tested (deterministic-core.md §3.2) | — |
| Q-28 | Done (4a) | `isd_min` added (V3/V1003 edited in place; **run `docker compose down -v` on a local database**) | — |
| Q-29 | Partly done | Pure `ToolCallBudget` policy built in 4a; wire the `TurnToolBudget` decorator | Phase 5a |
| Q-30 | Done (4a) | `InrFormat` in `domain`; the tools use it for GST-labelled strings | Phase 5a (use) |
| A-86 limitation | Open | VAS refund amount from BSS when the subscription predates the local history | After the MVP slice |

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
| 2026-09-25 | **Phase 3a go-ahead.** 3a answer 1: seed uses fixed calendar months, bills March to September 2026 (September = current), partitions 2026-01 to 2027-12 | Repo owner |
| 2026-09-25 | 3a answer 2: 7 bills per customer (current + 6 prior) | Repo owner |
| 2026-09-25 | 3a answer 3: create all slice tables in 3a. **Rule: until the first real deployment, edit V1–V6 in place instead of adding new migrations for changes.** (Anyone with a local database runs `docker compose down -v` or `flyway clean` after such an edit, since the checksums change) | Repo owner |
| 2026-09-25 | 3a answer 4: extra gate inside 3a: `seed-scenarios.md` is reviewed before any seed SQL is written | Repo owner |
| 2026-09-25 | 3a addition B: GST as on an Indian telecom invoice: tax on the bill-level taxable value; CGST 9% + SGST 9% intra-state, IGST 18% inter-state (configurable per account state); each component HALF_EVEN to 2 dp. Recorded as A-72 (FOR TAX REVIEW) | Repo owner |
| 2026-09-25 | **3a version decision (addition A): Stack B**: Spring Boot **4.1.1**, Spring AI **2.0.1**, Spring Modulith **2.1.1**. Boot 3.5.x and Spring AI 1.1.x OSS support ended on 2026-06-30 (spring.io). Anthropic SDK retries = 0, so Resilience4j is the only retry layer; move to Boot 4.2 / Spring AI 2.1 after GA. ADR-008; SPEC §3 and AGENTS.md updated | Repo owner |
| 2026-09-25 | Seed review round 1: tool-call cap excludes `escalateToHuman` and `recordDiagnosis` from the 8, **each at most once per turn** (implemented with a `TurnToolBudget` decorator, because Spring AI's `excludeToolFromLimit` still counts toward the total; the built-in limit is a backstop) | Repo owner |
| 2026-09-25 | **Q-20:** credits and refunds include GST; goodwill thresholds apply to the GST-inclusive amount; FOR TAX REVIEW | Repo owner |
| 2026-09-25 | **Q-21:** `simulatePlans` ranks plans (top 3) plus the best single add-on; no multiples | Repo owner |
| 2026-09-25 | **Q-22:** chat "meaningful increase" = ≥ ₹100 and ≥ 10%, documented as separate from the proactive `AnomalyDetector` rule (≥ 1.8x baseline or z > 2.5); per-scenario expectations table in seed-scenarios.md §5.7 | Repo owner |
| 2026-09-25 | **Q-23:** causes before GST plus a GST component; replies always say whether an amount includes GST and name a cause by its GST-inclusive figure (SPEC §4.6, PRD US-DIA-01, llm-architecture.md §10) | Repo owner |
| 2026-09-25 | **GST labels in replies (revises the grounding rule):** tools return pre-formatted, GST-labelled amount strings ("₹2,832.00 incl. GST"; "₹599 + GST" for catalogue prices); the model copies them verbatim; the grounding check verifies each amount and its label came from a tool result; on a label mismatch, regenerate once, then fall back to the template. SPEC §4.6, llm-architecture.md §6, §7, §10 updated | Repo owner |
| 2026-09-25 | **Q-24:** option (a), plus σ below a small configurable floor (₹1.00) counts as flat | Repo owner |
| 2026-09-25 | **Q-25:** add the 4b rule "new third-party VAS without double opt-in"; also an optional 4b rule "first bill after a plan change" → informational notification explaining proration (not an anomaly alert) | Repo owner |
| 2026-09-25 | **Q-26:** keep `AnomalyDetector` in 4b; 4a tests the chat column, 4b the proactive column | Repo owner |
| 2026-09-25 | **seed-scenarios.md approved**; data-architecture.md updated with the schema changes; go-ahead for the rest of Phase 3a | Repo owner |
| 2026-09-25 | **Phase 3a gate review:** both schema deviations accepted (`add_on` wide contents; `tariff_rate.unit_price NUMERIC(14,4)`). A-73: supplier-registered states become a config list (`billshock.tax.supplier-state-codes`); note that large Indian telcos register in each state they serve, so most postpaid bills would be intra-state; the seed keeps `[27]` so the IGST accounts stay covered; FOR TAX REVIEW. README "Run locally" section added | Repo owner |
| 2026-09-25 | **Phase 3a approved** | Repo owner |
| 2026-09-25 | **Phase 4a go-ahead.** **Q-27:** option (a): a period with a mid-cycle plan change mixes usage across two plans, so it is not representative for re-rating; `simulatePlans` returns `RECENT_PLAN_CHANGE` with the change date and no ranking; the rule expires after one full bill cycle on the new plan; the seed is not changed | Repo owner |
| 2026-09-25 | **Q-28:** option (a): add `isd_min` (and `isd_sms` only if the catalogue prices international SMS separately; it does not), editing V3/V1003 in place | Repo owner |
| 2026-09-25 | **Q-29:** pure counting policy in 4a, decorator wiring in 5a | Repo owner |
| 2026-09-25 | **Q-30:** Indian digit grouping (en-IN); the formatter is built now as a `domain` utility | Repo owner |
| 2026-09-25 | **Q-31:** one combined 4a gate: design note plus code, reviewed together | Repo owner |
| 2026-09-25 | 4a addition 1: `GuardrailContext` is always built server-side from read models, never from LLM tool arguments; stated in deterministic-core.md §4.1 and tested (a request cannot pass facts that bypass the read-model lookup) | Repo owner |
| 2026-09-25 | 4a addition 2: VAS refund assumption notes that a subscription activated before the local history needs the full refund amount from BSS; recorded as a limitation (A-86) | Repo owner |
| 2026-09-25 | **4a gate review:** deviations 1, 2, 3, 5 and 6 accepted | Repo owner |
| 2026-09-25 | 4a gate item 4 (changed): VAS refunds are exempt from the goodwill percentage and prior-credit rules, **not from the absolute limits**: above ₹2,000 incl. GST → escalate, as for goodwill. **No money-out action may be uncapped.** Implemented as `MoneyOutCap` (config `billshock.guardrails.money-out-escalate-above-inr`), with boundary tests | Repo owner |
| 2026-09-25 | 4a gate item 7: A-94 with Q-20 confirmed: thresholds are evaluated on the GST-inclusive amount, which the chain computes itself from the before-GST input with the bill-level rule; tests `ThresholdsUseTheGstInclusiveAmount` | Repo owner |
| 2026-09-25 | Phase 3a committed and merged to `main` as `0bc7d15`; 4a is on branch `phase-4a-engines` | Repo owner |
| 2026-09-25 | Phase 2 answer 7: A-58 to A-65 accepted. A-63 limitation documented (usage up to the previous day; no in-trip real-time alerts in v1; real-time usage events as a future enhancement) | Repo owner |

## Notes for the Phase 3a session

- **Pinned versions (ADR-008, decided 2026-09-25):** Spring Boot **4.1.1**, Spring AI
  **2.0.1** (BOM), Spring Modulith **2.1.1** (BOM), Java 21. Managed by Boot 4.1.1: Jackson
  3.1 (`tools.jackson`), Hibernate 7.4, Spring Security 7.1, Flyway 12.4 (needs
  `spring-boot-starter-flyway`), Testcontainers 2.0, PostgreSQL JDBC 42.7.13. Q-2 is
  verified in 2.0.1. **Never write imports from memory:** check against the pinned jars
  (AGENTS.md). *(Superseded: the Phase 2 plan of 3.5.16 / 1.1.8 / 1.4.13.)*
- **Migrations rule:** edit V1–V6 in place until the first real deployment (3a answer 3).
- The schema follows `data-architecture.md` (partition keys, primary keys that include the
  partition key, indexes in §7.1). The throwaway DDL used for EXPLAIN is a starting point
  but is not the Flyway schema.
- **docker-compose:** give PostgreSQL `shm_size: 256mb` or more. Docker's default 64 MB
  `/dev/shm` broke parallel `VACUUM` in the EXPLAIN test.
- Measure real row sizes (`pg_column_size`) on seed data and compare with A-54 and A-26.
- 5a: choose the current Haiku-tier model via llm-architecture.md §15 (Q-15); implement
  cost metering with prices per MTok (Q-17). 5b: stream-recovery API (Q-16).
- 5a reminders: build the Anthropic ChatModel beans explicitly (model, max tokens and
  timeout set; **`maxRetries(0)`**, ADR-008; temperature unset for Sonnet 5); keep customer data out of the system prompt; the `diffBills` pre-fetch
  goes in as a message; live temperature check only with owner approval.
- **7th seed scenario** (late roaming) stays scheduled for 6b; the data model supports it now
  (ADR-007).

## Phase 3a progress (2026-09-25)

**Step 0: version check (done, decided).** Findings are in ADR-008. Spring Boot 4.1.1,
Spring AI 2.0.1 and Spring Modulith 2.1.1 are GA; Boot 3.5.x and Spring AI 1.1.x OSS
support ended on 2026-06-30. Prompt caching exists in both stacks. Stack B was chosen.

Files changed (uncommitted; the owner commits):
- New: `docs/02-design/adr/008-framework-versions-boot4-spring-ai2.md`,
  `docs/03-development/seed-scenarios.md`
- Updated: `SPEC.md` §3; `AGENTS.md` (tech stack + the "never from memory" rule);
  `llm-architecture.md` (header, §1, **§2 re-checked against 2.0.1** with new rows F-8
  and F-9, §4, §9 built-in tool-call limit, §11 single retry layer, §14);
  `architecture.md` (ADR index, container label); ADR-005 and ADR-006 (version notes);
  `observability.md` §2; `feasibility.md` (T-6 resolved, T-8 closed); `assumptions.md`
  (A-64 note, **new §8 with A-71 to A-82**); `plan-and-budget.md` §2a (Boot 4)

**Re-check results against 2.0.1** (llm-architecture.md §2):
- The Anthropic module now uses the official Anthropic Java SDK. Its default of 2
  retries is overridden to 0.
- `effort` and adaptive thinking are available, which resolves T-6.
- No default temperature is sent, which closes T-8.
- Cache tokens are available in the standard usage data.
- There is a built-in tool-call limit (`ToolCallLimits`). §9 now uses it, with
  `escalateToHuman` and `recordDiagnosis` excluded from the count of 8. This exclusion
  is new; the owner should confirm it.
- `ToolExecutionEligibilityPredicate` was renamed to `…Checker`.
- JDBC memory still deletes and re-inserts the whole conversation, so A-64 stands.

**Seed design (for review):**
- `seed-scenarios.md` covers 6 accounts, 42 bills and 130 line items, with the
  CGST/SGST/IGST model and HALF_EVEN tie cases.
- Expected diff and simulation values are included.
- It proposes schema changes: `gst_state_code`, `place_of_supply`, `supply_type`,
  `tax_component`, `tax_rate` and service-period columns, and removes per-line
  `tax_amount`.
- It raises Q-20 to Q-23.

**Seed review round 1 (applied 2026-09-25):**
- Q-20 to Q-23 decided and recorded (seed-scenarios.md §5.7, §7, §11; A-72 extended; new
  A-83; SPEC §4.6; PRD US-DIA-01; llm-architecture.md §9, §10).
- **Correction found:** the built-in Spring AI limit cannot exclude tools from the total
  count (F-5), so §9 now uses a `TurnToolBudget` decorator, with the built-in limits as a
  backstop.
- New expectations table: chat vs proactive, per scenario. The proactive outcome for 1003
  and 1004 depends on Q-24.

**Phase 3a build (done 2026-09-25; at the gate):**

What exists now (all uncommitted; the owner commits):
- **Build:** Maven Wrapper (Maven 3.9.16), `pom.xml` on Boot 4.1.1 with the Spring AI
  2.0.1 BOM (no AI starters yet, A-82) and the Spring Modulith 2.1.1 BOM. Failsafe runs
  the `*IT` classes in `verify`, so `./mvnw test` stays unit-only (no Docker needed).
- **Modules:** 9 slice modules (`domain` open; `bss`, `analysis`, `tools`, `agent`,
  `actions`, `api`, `security`, `audit`), with allowed dependencies declared in each
  `package-info.java` to match architecture.md §4. `ModularityTests` verifies them and
  writes the module docs to `target/spring-modulith-docs`. A deliberate illegal
  dependency (`security` → `bss`) was tried, it failed the build, and it was removed.
- **Domain:** `Money` (BigDecimal, 2 dp, HALF_EVEN, no silent rounding), `BillPeriod`,
  `AccountId`, `SupplyType`, `GstCalculator` (A-72).
- **Flyway V1–V6:** all slice tables per data-architecture.md, including the approved
  GST columns. Monthly partitions from 2026-01 to 2027-12 (216) are created by a
  PL/pgSQL function; timestamptz bounds are pinned to UTC; there is no DEFAULT partition.
  CHECKs cover bill totals, TAX-line fields and period days. `audit_events` is
  append-only through a trigger. PostgreSQL 16 has no identity columns on partitioned
  tables, so `llm_call_log` and `audit_events` use sequences.
- **Seed** (`db/seed`, dev/test only): `V1001` catalogue (8 plans, 80 tariff rows, 5
  add-ons), `V1002` 6 accounts, 42 bills, 130 line items, `V1003` usage (42 period rows,
  roaming, and 372 daily rows generated in SQL by the A-81 rule). It matches
  seed-scenarios.md exactly.
- **bss:** a published API made of 5 TMF gateway interfaces (TMF678, TMF635, TMF620,
  TMF622, TMF621) and `BillingReadModel`, plus `TaxProperties`. Internals: read-only JPA
  entities and repositories with composite keys that include the partition key. There
  are in-process mock gateways (`mock-bss` profile, enabled by the `dev`/`test` profile
  groups) serving JSON fixtures, and their write calls are idempotent per key.
- **Config:** Flyway is enabled only in `dev`/`test` (A-82); JPA `ddl-auto: none`;
  `docker-compose.yml` has PostgreSQL 16 + pgvector (`shm_size: 256mb`, healthcheck);
  `.env.example` has new placeholder names.
- **Tests:**
  - 28 unit tests: modularity, Money, GST (all seed tie cases), BillPeriod, mock fixtures.
  - 63 integration tests on Testcontainers `pgvector/pgvector:pg16`:
    - schema: partitions, out-of-range insert, CHECKs, audit immutability
    - seed: all 42 bill totals of §5 with GST recomputed, supply type per state, the
      duplicate rental, usage charges = line items, daily = period, the UAE trip, the
      batch ledger
    - read model
    - catalogue fixture vs replica
- **Dev check:** `docker compose up` plus the jar with `SPRING_PROFILES_ACTIVE=dev`
  applied 9 migrations and started in 3 s with 42 bills. The stack was removed
  afterwards.
- **Row sizes** (`pg_column_size`, row data only): `bill` 106 B, `bill_line_item` 133 B,
  `usage_period` 97 B, `usage_daily` 99 B, `usage_period_roaming` 70 B,
  `usage_daily_roaming` 81 B. These are below A-26 and A-54 (notes added there).

Deviations from the docs, to confirm at the gate:
- `add_on` uses wide content columns plus `validity`, instead of `usage_type`/`units`,
  because roaming packs bundle data, voice and SMS.
- `tariff_rate.unit_price` is `NUMERIC(14,4)`: a rate, not an amount.
- data-architecture.md §4.2 has been updated to match both.

Not in 3a (as planned):
- In 3b: read-replica routing, retention and partition-maintenance job, WireMock, CI,
  code-quality plugins.
- Spring Security is not on the classpath yet (5a).
- `usage_daily` has no JPA mapping yet (added when a consumer needs it).

**Gate review changes (2026-09-25):**
- `TaxProperties.supplierStateCodes` is now a set (config `billshock.tax.supplier-state-codes`,
  seed `[27]`), and `SupplyType.of(state, registeredStates)` uses it. The seed IT checks
  the supply type against the configured list and asserts 3 intra-state and 3 inter-state
  accounts.
- A-73 was rewritten with the multi-state registration note (FOR TAX REVIEW), and
  seed-scenarios.md §2, §8, §10 and data-architecture.md §4.1 were updated to match.
- `README.md` is new, with a "Run locally" section and troubleshooting. Its steps were
  run as written with a temporary `.env` on port 55432: compose was healthy, the app
  started with 9 migrations, and `docker compose exec … psql` showed the 6 September
  totals. The host-`psql` command was not run, because there is no host psql on this
  machine.

## Phase 4a progress (2026-09-25)

**Status: built, at the gate.** `./mvnw verify` passes: 105 unit tests (was 28) and 94
integration tests (was 63). The design note and code are reviewed together (Q-31).

Files (all uncommitted; the owner commits):
- **New doc:** `docs/03-development/deterministic-core.md`: exact definitions of the diff,
  simulator, guardrail chain, tool-call budget and formatter.
- **Updated docs:**
  - `assumptions.md`: new §9 with A-84 to A-95
  - `seed-scenarios.md`: §4 example corrected and 1004 exception noted; §5.2 and §7.3
    decided; §9 ISD daily rule
  - `data-architecture.md` §4.3: `isd_min`
  - `architecture.md` §4: `actions` may use `analysis`
  - `llm-architecture.md` §9: `ToolCallBudget`
- **Schema (edited in place, 3a answer 3):**
  - `V3`: `isd_min` on `usage_period`, `usage_daily` and the `usage_as_used` view
  - `V1003`: 1006's ISD minutes, and the daily split
- **`domain`:** `InrFormat` (en-IN grouping, GST labels); `Money.average`, `negate`, `max`,
  `isPositive`, `isZero`.
- **`bss`:**
  - `CatalogReadModel` (JDBC over plan/tariff/add-on) and `RoamingBandProperties`
  - `BillingReadModel.latestBill`, `.roamingUsage` (new JPA entity for
    `usage_period_roaming`)
  - `UsagePeriodSummary.isdMin`
- **`analysis`:** `BillDiffEngine` + `BillDiff`, `CauseGroup`, `DuplicateChargeDetector`,
  `PlanSimulator` + `PlanSimulation`, `UsageRater`, `AnalysisProperties`.
- **`actions`:**
  - Public: `GuardrailService`, `ActionRequest` (7 request types), `GuardrailDecision`,
    `GuardrailProperties`
  - Internal `actions.guardrail`: context, factory, chain and 7 checks
- **`agent`:** `ToolCallBudget` + `ToolCallBudgetProperties`.
- **Config** (`application.yml`): `billshock.catalog.roaming-bands`, `billshock.analysis.*`,
  `billshock.guardrails.*`, `billshock.agent.tool-calls.*`.
- **Tests:**
  - Unit: `BillDiffEngineTest` (22), `PlanSimulatorTest` (15), `GuardrailServiceTest`
    (22), `ToolCallBudgetTest`, `InrFormatTest`, `MoneyTest` (average). They use in-memory
    seed fixtures (`SeedScenarioFixtures`) and a shared expectations table
    (`SeedExpectations`).
  - ITs: `AnalysisSeedIT`, `GuardrailSeedIT`, `CatalogReadModelIT`, plus new cases in
    `BillingReadModelIT` and `SeedDataIT` (ISD minutes × ₹6.00 = voice charge).

Results on the seed (unit and IT agree):
- **Chat column of seed-scenarios.md §5.7:** all 6 accounts match exactly (total, baseline,
  excess, %, ratio, verdict). 1006 is `NORMAL`, with no causes.
- **§7 causes:** all 5 flagged accounts give one cause, with exact excl./GST/incl. amounts;
  both rounding adjustments are ₹0.00.
- **Simulator:**
  - 1002: `PP_499` / `PP_599` / `PP_699` and `DATA_10GB`, as in §5.2
  - 1001: `IR_GCC_7D`, saving ₹876.00 / ₹1,033.68 incl. GST
  - 1003, 1005, 1006: no saving
  - 1004: `RECENT_PLAN_CHANGE` (1 Sep); its October bill is simulated normally (tested)
- **Guardrails:**
  - Astro Daily refund: ₹196.00 + ₹35.28 = ₹231.28, 4 line items
  - Cricket Scores refund: rejected (opt-in present)
  - Goodwill on the duplicate: rejected (`USE_DISPUTE`); the dispute is ₹706.82
  - Boundaries are exact at 15% (₹437.90 on 1001), ₹500.00 / ₹500.01 and ₹2,000.00 /
    ₹2,000.01
  - Another account's line item or subscription: rejected
- **Addition 1:** reflection tests show that the requests carry only choices and that the
  only public entry point is `evaluate(AccountId, ActionRequest)`. A recording proxy shows
  that every read-model and TMF622 lookup used the caller's account only.

Found and fixed during 4a:
- `VasCheck` put `CONFIRMATION_REQUIRED` on a rejected refund; caught by a test and fixed
  in the code.
- The seed-scenarios.md §4 example "1006 on `PP_399` = ₹952.96" was off by ₹1 (it is ₹951.96).
  It was an illustration, not a test value. Corrected.
- The same §4 statement ("no cheaper plan fits except 1002") is false for 1004 September.
  Q-27 handles this, and the doc now says so.

Deviations and choices to confirm at the gate:
1. **Module map:** `actions` now depends on `analysis`, so the goodwill guardrail reuses
   `DuplicateChargeDetector` instead of a copy. There is no cycle; `ModularityTests` passes;
   architecture.md §4 is updated.
2. **Config key:** the autonomy level is `billshock.guardrails.autonomy-level`, not the
   `billshock.actions.autonomy-level` from the 4a plan.
3. **Findings run on every bill,** whatever the verdict: a duplicate on a bill with a normal
   total is still reported. Causes are shown only for `MEANINGFUL_INCREASE`.
4. **A VAS refund is not subject to the goodwill limits** (it is policy-mandated). A
   refund above ₹2,000 would still only need the customer's confirmation.
5. **`catalog` read model** uses `JdbcClient` rather than JPA entities (small, read-only
   tables).
6. **`latestBill`** has no partition pruning: a backward index scan per partition with
   LIMIT 1. Fine at MVP scale; revisit with the retention job (3b).

Not in 4a (as planned): `AnomalyDetector` and the proactive columns of §5.7 (4b); the JaCoCo
gate (4b); `ProposedAction` persistence and executed-credit history (6a); the
`TurnToolBudget` decorator and the tools that call these engines (5a).

**Gate review changes (2026-09-25):**
- **Item 4:**
  - New `MoneyOutCap` (package `actions.guardrail`), used by `GoodwillCreditCheck` and
    `VasCheck`.
  - The cap moved from `billshock.guardrails.goodwill.escalate-above-inr` to
    `billshock.guardrails.money-out-escalate-above-inr`.
  - The reason code `CREDIT_ABOVE_ESCALATION_LIMIT` is renamed
    `MONEY_OUT_ABOVE_ESCALATION_LIMIT`.
  - Tests (`VasRefundCap`): a refund of ₹1,694.92 + ₹305.08 = ₹2,000.00 is proposed;
    ₹2,000.01 is escalated; ₹531.00 with a prior credit and above 15%/₹500 is proposed with no
    supervisor flag.
- **Item 7:** the chain computes GST itself on the before-GST input
  (deterministic-core.md §4.5, A-94). Tests (`ThresholdsUseTheGstInclusiveAmount`):
  - 1,694.93 before GST → ₹2,000.01 → escalated
  - the same ₹1,694.92 → ₹2,000.00 intra-state (proposed) but ₹2,000.01 inter-state
    (escalated)
  - 423.74 → ₹500.01 → supervisor
  - 1001: 400.00 → ₹472.00 > ₹437.90 → supervisor
- **Mutation check** (sources restored afterwards):
  - removing the VAS cap fails 1 test
  - comparing goodwill thresholds before GST fails 9 tests
- **Open:** the owner stated only the ₹2,000 cap for VAS refunds. The ₹500 supervisor limit
  does not apply to refunds. Confirm.

## Next step

Phase 4a gate review changes are applied. The owner confirms the open ₹500 point above
and A-84 to A-95, and commits on `phase-4a-engines`. Anyone with a local
database must run `docker compose down -v`, because the V3/V1003 checksums changed. Next:
**Phase 5a** (agent), **after the owner's go-ahead**.
