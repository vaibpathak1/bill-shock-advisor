# Progress Log

| Phase | Status | Notes |
|---|---|---|
| 1. Docs I | Done, awaiting approval | PRD, feasibility, capacity estimates, plan and budget, NFRs, assumptions register (2026-09-25) |
| 2. Docs II | Not started | |
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
- Run cost at 1x ≈ ₹78 lakh/month, of which LLM ≈ 79%. Spend passes the Scale tier's
  $200k/month cap at ~2.9x load.
- Unit cost ≈ ₹21 per chat conversation and ₹0.63 per proactive diagnosis. Break-even
  containment is 42% / 21% / 10.5% at a human contact cost of ₹50 / ₹100 / ₹200.

## Open questions

All Phase 1 questions are decided; see the log below. Only the follow-up actions remain.

| # | Status | Remaining action | When |
|---|---|---|---|
| Q-1 | Decided | Verify temperature handling with a live call | Phase 2 |
| Q-2 | Decided | Verify Anthropic prompt caching (and adaptive thinking) in Spring AI before pinning the version; **stop and tell the owner if unsupported** | Phase 3a, before pinning |
| Q-3 | Decided (with Q-7) | — | — |
| Q-4 | Decided | Define both SLIs and the hourly alert in `observability.md` | Phase 2 |
| Q-5 | Decided | Use it in `scalability.md` | Phase 2 |
| Q-6 | Decided (option C) | Detail the layout in `data-architecture.md`; open points in capacity-estimates §5.4 | Phase 2 |
| Q-7 | Decided | — | — |
| Q-8 | Decided | Encode as a routing rule in `data-architecture.md` | Phase 2 |
| Q-9 | Decided (defer) | Verify infra prices with the AWS Pricing Calculator before any real budget decision | Before a budget decision |
| Q-10 | Decided (plan) | Research India-region availability in **official Bedrock and Vertex AI docs only**, cited with retrieval date. If none exists, the owner decides the ADR-005 option | Phase 2 (ADR-005) |
| Q-11 | Decided | — | — |
| Q-12 | Decided | — | — |
| Q-13 | Deferred by agreement | Measure the real turn mix, then decide on Haiku-first routing | Phase 5b |

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

## Notes for the Phase 2 session

- The Phase 1 storage, IOPS and DB-cost figures (capacity §5.1, §5.3, §6; plan-and-budget
  §3.1) were derived with usage layout option A, so they are a **conservative upper
  bound**. `data-architecture.md` must re-derive them with option C (capacity §5.4). Then
  update the capacity summary and the DB lines of the budget.
- Data residency research (Q-10): official Bedrock and Vertex AI docs only, with
  retrieval dates.
- Live temperature check for `claude-sonnet-5` (Q-1).
- **Late roaming usage (TAP files).** Roaming usage arrives late through TAP files, days
  to weeks after the trip (A-57). Closed-period roll-ups (option C) must support
  **idempotent recomputation** when late usage arrives: the same late batch applied twice
  must give the same result. `data-architecture.md` must design for this and state how
  late records are billed, namely as prior-period charges on the next bill.
- **7th seed scenario (scheduled for 6b, not in the MVP slice):** late-arriving roaming
  charges from a previous period's trip, billed in the current cycle. The expected
  diagnosis names the original trip period, the country and the late amount. The agent
  must not present them as new roaming in the current period.

## Next step

Wait for approval of Phase 1. Then Phase 2 (Docs II): architecture, ADR-001 to ADR-006,
data architecture, scalability, security, LLM architecture, observability, following
the decisions and notes above.
