# Plan and Budget

| | |
|---|---|
| Status | Draft for review (Phase 1) |
| Date | 2026-09-25 |
| Spec reference | SPEC.md §1.5, §2.6, §11 |
| Inputs | [assumptions.md](assumptions.md), [capacity-estimates.md](capacity-estimates.md), [feasibility.md](feasibility.md) |

Currency: **INR is primary.** LLM costs are also shown in USD because they are billed in
USD. FX planning rate **₹88/USD** (A-36). 1 lakh = ₹100,000; 1 crore = ₹10,000,000.

---

## 1. Milestone timeline: enterprise team

Team (A-45): 1 Engineering Manager (EM), 4 backend engineers (BE1–BE4), 1 QA engineer,
0.5 SRE, 0.25 Product Owner (PO). Start **W0 = 2026-10-01**. Week offsets are
authoritative; dates are for convenience.

The enterprise plan **overlaps phases** where dependencies allow (interfaces agreed at
each phase's design gate). This differs from this repository's one-phase-at-a-time rule,
which applies to the solo reference build (§2).

| Phase (SPEC §11) | Weeks | Dates | Who | Exit gate |
|---|---|---|---|---|
| 1. Docs I: PRD, feasibility, capacity, plan, NFRs | W0–W2 | 2026-10-01 → 10-15 | PO, EM, BE1 | Approved docs; assumptions owners assigned |
| 2. Docs II: architecture, ADRs, data, scalability, security, LLM, observability | W2–W4 | 10-15 → 10-29 | BE1 (lead), SRE, security reviewer | ADR-001…006 accepted; R-05 (data residency) decided |
| 3. Foundation: skeleton, Modulith, Flyway, seed data, mock BSS, compose, CI PR workflow | W4–W7 | 10-29 → 11-19 | BE1–BE4, SRE | `./mvnw verify` green in CI; compose up |
| 4. Deterministic core: diff, anomaly, simulator, guardrails | W7–W11 | 11-19 → 12-17 | BE1, BE2 | Coverage gate 80% on `analysis/` |
| 5. Agent: ChatClient beans, tools, memory, prompt, SSE, fallback, PII masking | W8–W14 | 11-26 → 2027-01-07 | BE3, BE4 | Stubbed-LLM tool-wiring tests; first eval run |
| 6. Actions: ProposedAction, confirm, idempotency, audit, autonomy flags | W11–W15 | 12-17 → 01-14 | BE1, BE2 | Double-confirm test; audit completeness |
| 7. Async and scale: outbox, Kafka, proactive worker, Redis, rate limiting | W12–W17 | 12-24 → 01-28 | BE2, SRE | 80k diagnoses < 6 h in a stubbed run |
| 8. RAG and security: policy ingestion, Keycloak, injection suite | W14–W18 | 01-07 → 02-04 | BE3, BE4 | Injection suite passes |
| 9. Observability: metrics, traces, dashboards, alerts | W10–W18 (parallel) | 12-10 → 02-04 | SRE | 4 dashboards + alert rules in repo |
| 10. Testing depth: Pact, E2E, LLM evals, Gatling, chaos | W14–W22 | 01-07 → 03-04 | QA (lead), all BE | NFR baselines recorded; eval ≥ 95% |
| 11. Deploy: Helm, KEDA, Argo Rollouts, Terraform, CI/CD | W8–W22 (parallel) | 11-26 → 03-04 | SRE | Staging deployed by pipeline |
| 12. Ops docs + staging readiness: drills, runbooks, checklist | W20–W25 | 02-18 → 03-25 | SRE, QA, EM | Readiness checklist signed; restore and DR drills done |
| Legal review of R-05, R-08, R-09 (runs alongside) | W2–W20 | 10-15 → 02-18 | PO + Legal | Written sign-off before Level 0 |
| **Level 0 launch (canary)** | W25–W27 | 03-25 → 04-08 | All | Canary analysis passes |
| Contingency (~12%) | W27–W30 | 04-08 → 04-29 | — | — |
| **Level 0 GA (latest)** | **W30** | **2027-04-29** | | |
| Level 1 (propose) earliest | W34 | 2027-05-27 | | 4 weeks of Level 0 metrics reviewed |
| Level 2 (auto-approve) earliest | W38 | 2027-06-24 | | 4 weeks of Level 1 metrics + credit audit |

Scheduling risks: the festive season (October–November) and year-end leave fall in
W2–W14; legal review lead time is unknown; BSS partner availability for Pact
verification is outside the team's control.

People cost is **not** included in the run-cost model below (out of scope for SPEC 1.5).
The team is 6.75 FTE over ~30 weeks to Level 0 GA.

## 2. Solo timeline: this repository's reference implementation

Per A-46, this repository is built by **one engineer as a portfolio project**, with AI
coding assistance, one phase at a time with an approval gate after each (AGENTS.md).

| Phase | Full-time weeks (estimate) | Notes on reduced scope versus enterprise |
|---|---|---|
| 1. Docs I | 1 | — |
| 2. Docs II | 1.5 | — |
| 3. Foundation | 2 | — |
| 4. Deterministic core | 2 | — |
| 5. Agent | 3 | Real-model evals only when explicitly requested (cost) |
| 6. Actions | 2 | — |
| 7. Async and scale | 2.5 | Local Kafka/Redis only |
| 8. RAG and security | 2 | Keycloak local; no external pen test |
| 9. Observability | 1.5 | Local Prometheus/Grafana/Loki/Tempo |
| 10. Testing depth | 3 | Gatling against the stubbed LLM; soak shortened |
| 11. Deploy | 2 | `terraform plan` only, never `apply`; Helm lint/template; CI config written but not run against AWS |
| 12. Ops docs | 1 | Drills described and simulated locally; no real cross-region DR |
| **Total** | **≈ 23.5 weeks full-time** | At ~20 h/week, about 12 months |

What the solo build does **not** produce: real legal sign-off, real UAT with a care
team, a measured production RTO/RPO, or negotiated provider limits. These stay as
documented procedures and open ASSUMPTIONS.

## 2a. MVP demo slice (built first)

**Goal:** the smallest subset of Phases 3–6 that demos **all 6 seed scenarios end to
end**, running locally: chat → diagnosis → recommendation → propose → confirm, plus a
README. Everything not listed here comes **after** the slice.

**Execution (SPEC §11, decided 2026-09-25, Q-12):** the slice runs phase by phase, as
3a → 4a → 5a → 6a, with the usual approval gate after each. The remainder runs
afterwards as 3b → 4b → 5b → 6b, also gated, then Phases 7–12.

| Slice phase | In the slice | Deferred to the "b" phase or later |
|---|---|---|
| **3a Foundation** | Maven wrapper skeleton, Java 21, Spring Boot 3, Spring AI BOM (**version pinned only after verifying Anthropic prompt caching support, decision Q-2**); Modulith modules needed by the slice (`domain`, `bss`, `analysis`, `tools`, `agent`, `actions`, `api`, `security`, `audit`) + Modulith verification test; Flyway schema for the slice tables (monthly-partitioned DDL from day one, since changing it later is expensive); seed data for the 6 demo customers, 8 plans and 5 add-ons; in-process mock BSS gateways; docker-compose with PostgreSQL (pgvector image) only | Policy documents, synthetic data generator, WireMock, Redis/Kafka/Keycloak/MinIO/observability containers, CI PR workflow, Spotless/Checkstyle/SpotBugs/Semgrep, retention job, read-replica routing |
| **4a Deterministic core** | `BillDiffEngine` (with a materiality threshold, so scenario 6 comes out as "normal"), `PlanSimulator`, the guardrail chain for the rules the scenarios exercise (credit thresholds, VAS without opt-in → refund, plan change needs confirmation, max tool calls); unit tests with exact BigDecimal assertions | `AnomalyDetector` (needed only by proactive), JaCoCo gate |
| **5a Agent** | One `claude-sonnet-5` ChatClient bean (per-model temperature config, decision Q-1); read-only tools (`getBillSummary`, `getBillHistory`, `diffBills`, `getLineItems`, `getUsageDetails`, `getActiveSubscriptions`, `searchPlanCatalog`, `simulatePlans`); **`diffBills` pre-fetch + deterministic one-line spike summary** (decision Q-3/Q-7); deterministic fallback templates (the same template code); `prompts/system.v1.st`; JDBC chat memory; `POST /api/v1/chat` SSE; prompt caching enabled; MSISDN masking; Spring Security with in-memory demo users mapped to the 6 accounts, so identity really comes from the SecurityContext; stubbed-ChatModel tool-wiring test | Haiku ChatClient and proactive flow, `getPolicy`/RAG, Resilience4j tuning beyond a timeout + fallback, token-budget summarisation, Ollama profile, Keycloak, log masking pipeline |
| **6a Actions** | `ProposedAction` workflow; action tools needed by the scenarios (`proposeGoodwillCredit`, `proposeVasUnsubscribe`, `proposeThirdPartyBarring`, `proposePlanChange`, `proposeAddOn`, `raiseDispute`, `escalateToHuman`); `GET /api/v1/actions`, `POST /api/v1/actions/{id}/confirm` and `/reject` with `Idempotency-Key`; mock executors; append-only audit of tool calls and actions; autonomy fixed at Level 1 via one config flag | Level 2 auto-approval, supervisor queue and roles, audit API, runtime feature-flag kill switch, outbox events |
| **Demo wrap-up** | Minimal static HTML/JS chat page (SPEC §3) with confirm/reject buttons; **README** with setup, `docker compose up`, a demo script for the 6 scenarios and the expected outcomes; one stubbed-LLM end-to-end test per scenario in `./mvnw verify` | Full E2E suite, evals, Gatling, Pact (Phase 10) |

The real-model demo runs only when you ask, since it costs money (AGENTS.md).

**Solo estimate (full-time, A-46):**

| Slice phase | Weeks | Full phase (from §2) |
|---|---|---|
| 3a | 1.5 | 2 |
| 4a | 1.5 | 2 |
| 5a | 2 | 3 |
| 6a | 1.5 | 2 |
| Demo wrap-up (UI, README, scenario E2E) | 0.5 | — |
| **MVP demo slice** | **≈ 7 weeks** | 9 weeks for full Phases 3–6 |
| Remainder 3b–6b | ≈ 2.5 weeks | |

The solo total rises slightly, from ~23.5 to ~24 weeks, because of the demo wrap-up. In
exchange, a working end-to-end demo exists about **8.5 weeks** after Phase 1 approval
(Phase 2 at 1.5 weeks + the slice at 7 weeks). Finishing full Phases 3–6 first would take
about 11 weeks (1.5 + 9 + 0.5 wrap-up). The main gain is lower risk: the riskiest
integration (agent → tools → confirm) is proven before the infrastructure-heavy work.

## 3. Monthly run-cost model

### 3.1 Infrastructure (production)

> **INDICATIVE AND UNVERIFIED (A-34).** The unit costs are rough AWS ap-south-1 on-demand
> planning figures. They were **not** checked against the AWS price list on 2026-09-25.
> Replace them with AWS Pricing Calculator output before any budget decision. Sizes follow
> capacity-estimates.md.

| Component (sizing basis) | 1x USD | 3x USD | 10x USD |
|---|---|---|---|
| EKS control plane (1 cluster) | 75 | 75 | 75 |
| Worker nodes: app pods, Keycloak, observability (8 → 24 → 80 × 4 vCPU/16 GiB) | 1,200 | 3,600 | 12,000 |
| RDS PostgreSQL primary, Multi-AZ (16 vCPU/128 GiB → 2x → 4x at 10x; **no sharding with usage option C**, Phase 2) | 4,500 | 9,000 | 18,000 |
| RDS read replica(s) (1 → 1 → 1 large or 2) | 2,250 | 4,500 | 9,000 |
| DB storage, provisioned IOPS, backups/PITR (**option C, Phase 2:** 0.82 TB → 2.5 TB → 8.2 TB, ×2 Multi-AZ; was 5.4 → 16 → 54 TB under option A) | 350 | 1,000 | 3,300 |
| ElastiCache Redis (primary + replica; 4 → 12 → 37 GB) | 500 | 1,000 | 4,000 |
| MSK Kafka (3 brokers + storage) | 750 | 1,500 | 5,000 |
| S3 (archives, eval reports) | 75 | 150 | 500 |
| Load balancer, NAT gateways, data transfer | 600 | 1,500 | 5,000 |
| Observability storage (metrics, logs, traces) | 400 | 1,000 | 3,000 |
| Secrets Manager, KMS, WAF, misc. | 150 | 300 | 1,000 |
| **Production infra total** | **10,850** | **23,625** | **60,875** |
| **in INR** | **₹9.5 lakh** | **₹20.8 lakh** | **₹53.6 lakh** |

**Phase 2 update:** the DB lines were re-derived with usage layout option C
(data-architecture.md §9). The 10x column no longer assumes sharding; one large primary
plus replicas is enough (data-architecture.md §8). Under option A, the totals were $12,650 /
$29,075 / $106,075. (**Correction:** Phase 1 printed the 3x total as $27,075, but its rows add up
to $29,075, so the Phase 1 3x run cost was understated by ₹1.8 lakh/month.) Non-production environments (dev, qa, staging) add a fixed 50% of 1x
prod (A-33): **$5,425 = ₹4.8 lakh/month** at every load point.

### 3.2 LLM (USD billed; INR shown)

Prices from A-20 (Anthropic list prices, 2026-09-25), with prompt caching per A-16.

| Line | 1x | 3x | 10x |
|---|---|---|---|
| Live chat: `claude-sonnet-5` (1.92 M turns × $0.0345) | $66,240 | $198,720 | $662,400 |
| Proactive: current Haiku-tier model (config) (400k × $0.0072, Haiku 4.5 prices) | $2,880 | $8,640 | $28,800 |
| Evals and experiments (A-47, fixed) | $500 | $500 | $500 |
| **LLM total (USD)** | **$69,620** | **$207,860** | **$691,700** |
| **LLM total (INR)** | **₹61.3 lakh** | **₹182.9 lakh** | **₹608.7 lakh** |
| *Chat without prompt caching (for comparison)* | *$152,640* | *$457,920* | *$1,526,400* |

**Reconciliation (1x):**

```
chats/month × LLM cost per chat         = 320,000 × $0.2070  = $66,240
diagnoses/month × cost per diagnosis    = 400,000 × $0.0072  =  $2,880
evals and experiments (A-47)                                 =    $500
                                                               --------
LLM monthly total                                            = $69,620  ✓ matches the table
```

- **Chats/month** (A-09): both rates are per bill in one cycle-day's population
  (2,000,000 bills), per day. Peak days: 10 × (1% × 2M) = 200,000. Other days: 20 ×
  (0.3% × 2M) = 120,000. The off-peak rate is therefore **per bill, not per subscriber**:
  0.3% of 2M bills = 6,000 chats/day, which is 0.06% of the 10M subscribers per day.
- **Cost per chat:** 6 turns × $0.0345 (feasibility §3.2) = $0.2070.
- **Diagnoses/month:** 5 cycles × 80,000 flagged bills = 400,000, at $0.0072 each.
- The 3x and 10x columns are exactly 3× and 10× these lines, plus the fixed $500.
- The baseline keeps **3 round trips per turn**. The accepted `diffBills` pre-fetch
  (decision Q-7, C-4) removes one round trip on the first turn only. That would give an
  average of 2.83 and save about $3,700/month at 1x. It is left out of the baseline until
  Phase 5 measures it.

### 3.2a Scenario: Haiku-first routing (not the baseline)

Investigation and recommendation turns stay on `claude-sonnet-5`, and the first turn of a
conversation is always one of them. Simple and follow-up turns go to
the current Haiku-tier model (config). The routing split is a new **ASSUMPTION (A-50)**, with the
Haiku turn profile in A-51 and A-52.

Per-turn cost:
- Sonnet turn: $0.0345 (unchanged).
- Haiku turn: 1.5 × (12,000 × 0.6625 × $1 + 200 × $5) / 1M = **$0.0134**. The 0.6625
  multiplier reflects the extra cache writes, because caches are per model.

| Share of turns on Haiku (A-50) | Sonnet chat cost | Haiku chat cost | **Chat LLM / month** | Saving vs baseline $66,240 | LLM per chat |
|---|---|---|---|---|---|
| 20% | $52,992 | $5,155 | $58,147 | −$8.1k (−12%) ≈ ₹7.1 lakh | $0.182 (₹16.0) |
| **40% (A-50)** | $39,744 | $10,310 | **$50,054** | **−$16.2k (−24%) ≈ ₹14.2 lakh** | **$0.156 (₹13.8)** |
| 60% | $26,496 | $15,466 | $41,962 | −$24.3k (−37%) ≈ ₹21.4 lakh | $0.131 (₹11.5) |

Effects beyond cost:
- **Rate limits.** At 40%, Sonnet 5's peak-minute counted ITPM at 1x falls from 4.05 M to
  about 2.4 M. Haiku has its own bucket, so Sonnet headroom improves and the year-3
  custom-limit trigger moves later.
- **Quality risk.** A misrouted turn that needs investigation would be answered by the
  weaker model. Proposed guard: Haiku turns get only read-only, already-computed tools.
  If they need investigation, they hand back to Sonnet. Routing is deterministic (rules
  on turn intent and state), not a third LLM call. Haiku turns must pass the same eval
  gate (NFR-07/08).
- **Latency.** A hand-back adds a round trip. Haiku is typically faster on the turns it
  keeps.
- **Decision point:** Phase 5, after measuring the real turn mix. Until then the budget
  baseline stays all-Sonnet.

### 3.3 Total monthly run cost

| | 1x | 3x | 10x |
|---|---|---|---|
| Production infra | ₹9.5 lakh | ₹20.8 lakh | ₹53.6 lakh |
| Non-prod infra | ₹4.8 lakh | ₹4.8 lakh | ₹4.8 lakh |
| LLM (incl. evals) | ₹61.3 lakh ($69.6k) | ₹182.9 lakh ($207.9k) | ₹608.7 lakh ($691.7k) |
| **Total per month** | **₹75.6 lakh** | **₹208.5 lakh** | **₹667.0 lakh** |
| Total per month (USD equivalent) | $85.9k | $236.9k | $758.0k |
| LLM share of total | 81% | 88% | 91% |
| **Per year** | **₹9.1 crore** | **₹25.0 crore** | **₹80.0 crore** |

Totals are computed in USD and converted at ₹88 (A-36), so the rounded INR rows can differ
by ₹0.1 lakh. Phase 1 (option A) totals were ₹78.0 / ₹212.3 / ₹707.6 lakh (the 3x figure should have been ₹214.1 lakh; see the correction in §3.1).

Observations:
- **LLM is ~81% of run cost** (after the Phase 2 DB re-derivation), and live chat is ~95% of LLM cost. Cost controls must focus
  on chat tokens.
- At 3x, LLM spend ($207.9k/month) **exceeds the Scale tier's $200,000 monthly spend cap**
  (A-21). A Custom tier or enterprise agreement is required before that point, and
  enterprise pricing is negotiable.
- Unit cost is ~₹20.6 per chat conversation (₹21.0 in Phase 1, before the DB re-derivation)
  and ~₹0.63 per proactive diagnosis (feasibility §3.2).

## 4. Cost controls

| # | Control | Estimated effect (1x) | Status |
|---|---|---|---|
| C-1 | **Prompt caching** (stable prefix: system prompt + tools; history cached; 5-min TTL) | Chat LLM $152.6k → $66.2k (**−57%**). Also required for rate limits | **Baseline requirement** (Phase 5); cache hit rate is a monitored metric |
| C-2 | **Diagnosis cache per `bill_id`** (Redis L2, SPEC 2.3) | Avoids re-running engines and BSS calls when chat follows a proactive diagnosis (A-22). LLM effect small | Baseline (Phase 7) |
| C-3 | **Model routing:** the current Haiku-tier model (config) for proactive (baseline). **Haiku-first routing** for simple/follow-up chat turns (§3.2a) | At the A-50 split of 40%: −24% chat LLM (−$16.2k/month). Caches are per model; must pass the eval gate | Scenario; decide in Phase 5 |
| C-4 | **Fewer round trips:** deterministic `diffBills` pre-fetch before the first LLM call (**accepted**, decision Q-7) plus parallel tool calls | Pre-fetch alone: 3 → 2.83 average round trips, −5.6% (~$3.7k/month). With parallel tool calls reaching 2.5 average: −17% (~$11k/month) | Pre-fetch accepted (SPEC 4.6); saving measured in Phase 5 |
| C-5 | **Token budgets per turn** (SPEC 2.6): max tool calls 8; max input tokens per turn; max output tokens; truncate or summarise memory beyond N messages | Caps the tail; protects against runaway loops | Baseline (Phase 5) |
| C-6 | **`effort` tuning** on Sonnet 5 (low/medium) to limit adaptive-thinking tokens | Output tokens are ~22% of chat LLM cost; the saving depends on the eval result | Phase 5 experiment |
| C-7 | **Batch API** for proactive, with a synchronous fall-back for items not done by T+4 h | −$1.4k/month | Optional (Phase 7) |
| C-8 | **Per-customer rate limit** (20 turns/hour, SPEC 2.3) and global limit | Blocks abuse-driven cost | Baseline (Phase 7) |
| C-9 | **Cost alerts:** max cost per conversation (start at ₹45, 2x expected); cost per hour; workspace spend limits in the Anthropic Console below the tier cap | Early warning for a silent cache regression (which alone can double cost) | Baseline (Phase 9) |
| C-10 | **Commercial:** enterprise agreement and volume discount once spend is predictable | Negotiable; not modelled | Before ~2.9x load |

## 5. Budget governance

- **Monthly cost review** (SPEC §10): tokens by model, cache hit rate, cost per
  conversation, cost per resolved case (K-05), and spend against the tier cap.
- **Re-baseline** this model after Phase 10 using measured tokens per turn, round trips and
  cache profile. Then update A-14 to A-16 in the register.
- **Re-check list prices** (A-20) before each budget cycle. They are config values, not
  code.
