# Feasibility Study

| | |
|---|---|
| Status | Draft for review (Phase 1) |
| Date | 2026-09-25 |
| Spec reference | SPEC.md §1.3, §2.5, §2.6 |
| Inputs | [assumptions.md](assumptions.md), [capacity-estimates.md](capacity-estimates.md) |

## 1. Verdict

**Feasible, with five conditions:**

1. **Prompt caching is a hard requirement.** Without it, the 1x chat peak needs 16.2 M
   input tokens/min, above the Scale tier's 10 M limit, and cost per conversation more
   than doubles. With it, 1x uses 41% of Scale-tier ITPM (capacity §4.3).
2. **Every amount comes from deterministic engines** (ADR-003). This is what makes the
   product safe and makes the LLM-outage fallback possible.
3. **Data residency is unresolved** (R-05). It may change the provider route and must be
   settled in Phase 2 (ADR-005) before any production data is processed.
4. **Regulatory questions need legal review** (R-08, R-09) before Level 0 launch.
5. **A custom rate limit or enterprise agreement** is needed from about year 3, or from
   2.9x load for the spend cap. It is not needed at launch.

Rate limits are **not** the largest feasibility risk at 1x. The largest risks are
**financial harm from wrong credits or amounts** (R-01, R-02), **data residency** (R-05)
and **regulatory exposure** (R-08, R-09).

---

## 2. Technical feasibility

### 2.1 Data availability through BSS APIs

The v1 build uses mock gateways modelled on TM Forum Open APIs (SPEC 4.1). Feasibility in
production depends on the operator's real BSS exposing the data below.

| Data needed | Tool(s) | TMF API | Availability (ASSUMPTION) | Gap / risk |
|---|---|---|---|---|
| Current and past bills, line items | `getBillSummary`, `getBillHistory`, `getLineItems`, `diffBills` | TMF678 Customer Bill | Available for 6+ months | Line-item categorisation may differ by BSS; needs a mapping table |
| Daily usage aggregates | `diffBills`, `simulatePlans`, anomaly detector | TMF635 Usage (bulk/export) | Nightly feed (A-24) | Feed format and completeness are unknown; late CDRs can change aggregates after billing |
| Detailed usage per session | `getUsageDetails` | TMF635 Usage (query) | On-demand query (A-25) | Query latency under load unknown; must tolerate BSS timeouts (R-15) |
| Plan and add-on catalog, prices | `searchPlanCatalog`, `simulatePlans` | TMF620 Product Catalog | Available | Complex tariff rules (fair-use, bundles) may not be fully expressible; the simulator covers the v1 catalog only |
| Subscriptions with activation channel | `getActiveSubscriptions` | TMF622 / product inventory | Available | — |
| **VAS double opt-in evidence** | `getActiveSubscriptions`, guardrail | Often a separate consent platform, not TMF | **Uncertain** | If the evidence cannot be fetched, the guardrail "no double opt-in → full refund" (SPEC 4.5) cannot be applied automatically. Fallback: treat as unknown and escalate |
| Orders (plan change, add-on, barring) | Action executors | TMF622 Product Ordering | Available | Must be idempotent on the BSS side, or we de-duplicate on our side |
| Adjustments / credits | Credit executor | TMF678 (adjustment) or billing API | Assumed available | Some BSS only apply credits on the next bill; the explanation must say so |
| Disputes | `raiseDispute` | TMF621 Trouble Ticket | Available | — |
| Contact history (KPIs K-01, K-06) | Analytics only | CRM | A-44 | Needed for measurement, not for the product |

**Conclusion:** feasible for the six reference scenarios. The two material data risks
are the VAS opt-in evidence and the quality of the usage feed. Both are recorded in R-15.

### 2.2 LLM tool-calling reliability

| Concern | Mitigation (where it is built) |
|---|---|
| Wrong tool or wrong arguments | Few, well-described tools with strict JSON schemas; identity never taken from arguments (SPEC 4.3); argument validation in Java (Phase 5) |
| Tool loops / runaway calls | Guardrail: max 8 tool calls per turn, then escalate (SPEC 4.5); token budget per turn (SPEC 2.6) |
| Skipping investigation | System prompt requires `diffBills` first; evals assert the expected tool sequence (SPEC 5) |
| Invented causes | Grounding rule; structured `BillShockDiagnosis` validated against engine output; scenario 6 eval must produce 0 issues |
| Regression after model or prompt change | Versioned prompts; eval gate in staging CI (≥ 95% primary-cause accuracy); canary (SPEC 8.1) |

Reliability target (NFR): ≥ 95% correct primary cause on the eval set. **It is feasible
because the LLM's job is narrow:** choose tools and explain numbers it is given. It does
not compute anything. Real accuracy is first measured in Phase 5/10 (evals cost money and
run only on request).

### 2.3 Latency

Estimated budget for a typical turn with 3 LLM round trips (A-14, A-19):

| Step | Estimate |
|---|---|
| Auth, rate limit, load chat memory | 50 ms |
| LLM call 1 (decides on tools, e.g. `diffBills` + `getActiveSubscriptions` in parallel) | ~3 s |
| Tools (cached diagnosis ~50 ms; BSS detail call up to ~500 ms) | 0.1–0.5 s |
| LLM call 2 (more tools, e.g. `simulatePlans`) | ~3 s |
| LLM call 3 (streams the answer, ~250 visible tokens) | first token ~1–1.5 s; complete ~4 s |
| **Time to first *answer* token** | **~7–8 s** |
| **Full response** | **~11–12 s** (p95 at risk with 5 round trips) |

> **Update 2026-09-25 (Phase 1 review):** the `diffBills` pre-fetch is accepted (Q-7), and
> the TTFT target is replaced by *first meaningful content p95 < 1.5 s* (a deterministic
> spike summary) and *first LLM-generated word p95 < 8 s* (Q-3). With the pre-fetch, the
> first turn needs one fewer LLM call. The findings below are the original analysis that
> led to the decision.

Findings:
- **Full response p95 < 15 s is feasible but tight.** It depends on keeping round trips
  near 3 (parallel tool calls, the tool-call guardrail).
- **TTFT p95 < 3 s is not achievable if "first token" means the first token of the
  answer text**, because the answer only starts after the tool rounds. It is achievable if
  TTFT means the first user-visible SSE event, such as an immediate progress event
  ("Checking your last 3 bills…"). The definition is an **open question** (see nfr.md).
- Candidate optimisation for Phase 2: run `diffBills` deterministically **before** the
  first LLM call and put its result in the context. This saves one round trip (about 3 s
  and ~33% of chat LLM cost) on the first turn. It changes SPEC 4.6's "call diffBills
  first" from a prompt rule into orchestration, so it needs approval.

### 2.4 Model and API constraints found during Phase 1

These are recorded now because they affect later phases. Each must be re-verified
against the Spring AI version in `pom.xml` before use (AGENTS.md).

| # | Finding | Source | Impact | Proposed handling |
|---|---|---|---|---|
| T-1 | **`claude-sonnet-5` rejects sampling parameters** (`temperature`, `top_p`, `top_k` return HTTP 400). SPEC 2.6 asks for temperature 0.2 | Claude API reference (skill doc cached 2026-06-24); verify in Phase 2 | **Conflict with SPEC 2.6** | **Decided (Q-1):** temperature is a per-model config property, omitted for models that reject it; verify with a live call in Phase 2. SPEC 2.6 updated |
| T-2 | Sonnet 5 runs **adaptive thinking** by default. Thinking tokens are billed as output | Same | Output cost and latency | Budgeted in A-15 (250 output tokens per call). Tune `effort` (low/medium) in Phase 5 |
| T-3 | Newer tokenizer (Claude 4.7 and later) gives ~30% more tokens for the same text | Pricing page, 2026-09-25 | Token estimates | A-37: estimates are already in model tokens |
| T-4 | Prompt caching: cache reads don't count toward ITPM and cost 0.1x input | Pricing and rate-limit pages, 2026-09-25 | Makes 1x fit the Scale tier | **Spring AI must support Anthropic `cache_control`** in the version we pin. **Decided (Q-2):** verify before pinning the Spring AI version in Phase 3; if unsupported, stop and escalate to the owner (the cost model depends on it). SPEC 2.6 updated |
| T-5 | Model id alias: the SPEC uses `claude-haiku-4-5-20251001`; the Claude API also lists the alias `claude-haiku-4-5` | Claude API reference | Config only | Keep the SPEC's id in config; model names come from config anyway (SPEC 2.6) |

---

## 3. Financial feasibility

All prices are config parameters (A-20). Figures below use the 2026-09-25 list prices, the
FX planning rate of ₹88/USD (A-36) and the assumptions register.

### 3.1 Cost-per-conversation model

```
LLM cost per conversation =
    T × R × [ I × (f_read × m_read + f_write × m_write + f_uncached) × P_in
              + O × P_out ] / 1,000,000

where
  T = turns per conversation            (A-09: 6)
  R = LLM round trips per turn          (A-14: 3)
  I = input tokens per LLM call         (A-15: 12,000)
  O = output tokens per LLM call        (A-15: 250)
  f_read / f_write / f_uncached         (A-16: 0.75 / 0.20 / 0.05)
  m_read / m_write                      (pricing: 0.1 / 1.25 for the 5-min TTL)
  P_in / P_out  USD per MTok            (A-20: Sonnet 5 $2 / $10)

Total cost per conversation = LLM cost + infra share
  infra share = 80% of 1x prod infra ÷ conversations per month
```

Config keys (proposed): `billshock.llm.pricing.<model>.input-per-mtok`, `…output-per-mtok`,
`…cache-read-multiplier`, `…cache-write-5m-multiplier`, `billshock.cost.fx-usd-inr`,
`billshock.cost.human-contact-inr`, `billshock.cost.max-per-conversation-inr`.

### 3.2 Worked example (1x)

| Item | Calculation | USD | INR |
|---|---|---|---|
| Effective input multiplier | 0.75×0.1 + 0.20×1.25 + 0.05×1.0 | 0.375 | |
| Input per turn | 3 × 12,000 × 0.375 × $2 / 1M | $0.0270 | |
| Output per turn | 3 × 250 × $10 / 1M | $0.0075 | |
| **LLM per turn** | | **$0.0345** | ₹3.04 |
| **LLM per conversation** (6 turns) | | **$0.207** | **₹18.2** |
| Infra share | 80% × $12,650 ÷ 320,000 conversations (plan-and-budget §3) | $0.032 | ₹2.8 |
| **Total per conversation** | | **$0.239** | **₹21.0** |
| *Same, no prompt caching* | input multiplier 1.0 | *$0.509* | *₹44.8* |
| **Proactive diagnosis** (Haiku 4.5, 1 call) | (4,000 × $1 + 2,000 × $0.10 + 600 × $5) / 1M | $0.0072 | ₹0.63 |
| Proactive with Batch API (−50%) | option; see §3.5 | $0.0036 | ₹0.32 |

The NFR "configurable max cost per conversation; alert above it" should start at around
**₹45** (2x the expected cost), per A-35/A-36 config.

### 3.3 Comparison with a human care contact

Human contact cost is a placeholder at three values (A-35). Containment (share of
conversations resolved without a human, A-38) is the other unknown.

**Net saving per bill-shock conversation** = containment × human cost − AI cost (₹21.0).
A conversation that escalates costs the AI cost **plus** the human contact.

| Containment ↓ / Human cost → | Low ₹50 | Mid ₹100 | High ₹200 |
|---|---|---|---|
| 20% | −₹11 | −₹1 | +₹19 |
| **40% (A-38)** | **−₹1** | **+₹19** | **+₹59** |
| 60% | +₹9 | +₹39 | +₹99 |

**Break-even containment** = AI cost ÷ human cost: **42%** at ₹50, **21%** at ₹100,
**10.5%** at ₹200. Without prompt caching (₹44.8 per conversation) it rises to 90% / 45% /
22%.

**Monthly net effect at 1x** (320,000 conversations, 40% containment): **−₹3.2 lakh** at
₹50, **+₹60.8 lakh** at ₹100, **+₹188.8 lakh** at ₹200 per contact.

**Cost per resolved case (K-05)** at 40% containment = ₹21.0 ÷ 0.4 = **₹52.5**.

What the model leaves out (so treat it as indicative):
- It **overstates** savings by assuming every AI conversation would otherwise have been a
  human contact. Some would never have been contacts at all.
- It **understates** benefits by ignoring AHT reduction on escalated contacts (the human
  gets a summary), contacts avoided entirely by proactive outreach, and churn or dispute
  reduction.
- It excludes build and people cost, BSS integration work, notification channel cost
  (A-39) and enterprise discounts.

**Conclusion:** the unit economics work **if the real human contact cost is at or above
~₹60 and containment reaches ~35–40%**. At the low placeholder (₹50), the case rests on
the benefits not modelled here. Validating A-35 and A-38 is the most important financial
action.

### 3.4 Proactive flow economics

400,000 diagnoses per month × ₹0.63 = **₹2.5 lakh/month** of LLM cost ($2,880). If one
proactive notification in 20 prevents a human contact, that is 20,000 contacts avoided,
worth ₹10–40 lakh at the A-35 range. The proactive flow is cheap relative to its
potential.

### 3.5 Batch API for proactive diagnosis

The Batch API halves Haiku cost ($1,440/month saved at 1x). But batch results are not
guaranteed within the 6 h NFR. **Recommendation:** keep synchronous calls as the baseline.
Consider the Batch API in Phase 7 only with a fall-back to synchronous calls for items not
done by T+4 h. The saving is small next to chat cost.

---

## 4. Provider capacity (summary)

Details are in [capacity-estimates.md §4](capacity-estimates.md).

| Load point | Sonnet 5 counted ITPM vs Scale tier | Needs custom limits? |
|---|---|---|
| 1x | 4.05 M (41%) | No |
| 3-yr | 7.0 M (70%) | At the edge. **Plan the negotiation ~6 months ahead** |
| 10x | 40.5 M (405%) | **Yes** |
| Stress (40 turns/s) | 21.6–36 M | **Yes** (or test with the stubbed LLM) |
| Monthly spend > $200k Scale cap | reached at ~2.9x load | **Yes**, enterprise agreement |

Enterprise rate limits and pricing are negotiable with Anthropic's account team (A-21).

---

## 5. Risk register

Likelihood (L) and Impact (I): H / M / L. Owners are roles. Legal items are
**FOR LEGAL REVIEW**: this document does not claim compliance or interpret the law.

| ID | Risk | L | I | Mitigation | Owner | Phase |
|---|---|---|---|---|---|---|
| R-01 | **Hallucinated amounts:** the agent states a ₹ figure not in the data | M | H | LLM never computes (ADR-003); amounts only from tool results; structured output validated against engine output before sending; grounding evals; fallback template | Tech lead | 4, 5, 10 |
| R-02 | **Wrong or excessive credits** | M | H | ProposedAction + confirmation; guardrail chain in Java with thresholds from config; idempotency keys; ₹ auto-credited per hour alert (SPEC 2.7); autonomy ladder starting at Level 0; supervisor queue; runbook "wrong credits" | Product + billing ops | 4, 6, 9 |
| R-03 | **Prompt injection**, direct ("ignore your instructions") or indirect (malicious text in a VAS name or policy document) | H | H | Identity from SecurityContext only; tools cannot reach other accounts; tool outputs treated as data; injection test suite (SPEC 5); no action executes without confirmation; OWASP LLM Top 10 mapping in security.md | Security | 5, 8 |
| R-04 | **Sensitive information disclosure / PII sent to the LLM** | M | H | Only masked MSISDN (last 4 digits), no name or address, usage and charges only (SPEC 2.5); PII masking in logs; data-flow diagram marking PII boundaries (Phase 2) | Security / DPO | 2, 5 |
| R-05 | **Data residency (OPEN).** Where the LLM processes customer data. The Anthropic first-party API lists only `global` (default) and `us` inference geographies (pricing page, 2026-09-25). Whether Claude is offered in an **India** region through a cloud provider (Bedrock or Vertex regional endpoints) is **not verified** | M | H | **Verify in Phase 2 for ADR-005**; do not assume. Minimise data sent (R-04). Options: in-country cloud route if available; legal assessment of cross-border transfer; the deterministic fallback as a no-LLM mode | Architect + legal | 2 |
| R-06 | **LLM provider outage or degradation** | M | M | Resilience4j timeout, retry on 429/5xx, circuit breaker; deterministic template fallback (SPEC 2.6); fallback-rate alert > 5%; outage drill (SPEC 7) | SRE | 5, 9 |
| R-07 | **Provider rate limits and spend cap** | L at 1x; H at 10x | M | Prompt caching (required); workspace quotas for chat vs batch; proactive semaphore; custom limits negotiated ahead (capacity §4.6); stubbed LLM for load tests | Architect | 5, 7, 10 |
| R-08 | **DPDP Act 2023 obligations** (consent, purpose limitation, retention, data-principal rights, processor contracts with the LLM provider) — **FOR LEGAL REVIEW** | M | H | Retention periods proposed only as placeholders (A-29); purpose-limited data minimisation; compliance section in security.md written for legal review; no compliance claim | Legal / DPO | 2 |
| R-09 | **Indian telecom consumer-protection rules on usage alerts and billing transparency** (for example, rules from the sector regulator on usage notifications, bill content and complaint handling) — **FOR LEGAL REVIEW**. This document makes **no claims about their specific requirements** or whether this product meets them | M | H | Legal to identify applicable rules and whether the proactive notification or chat explanation creates or satisfies obligations; product copy and notification timing adjustable by config; audit trail supports evidence | Legal / regulatory affairs | 1–2, before Level 0 |
| R-10 | **Tool-calling errors or loops** | M | M | Guardrail 8 tool calls; strict schemas; evals on expected tool sequence; token budget per turn | Tech lead | 5, 10 |
| R-11 | **False positives on normal bills** (invented issues, unnecessary outreach) | M | M | Deterministic anomaly detector with tuned thresholds; scenario 6 eval must show 0 issues; outreach only for flagged bills | Tech lead | 4, 10 |
| R-12 | **Latency NFRs** (TTFT, 15 s full response) not met with multi-round tool loops | H (TTFT as answer token) / M (full response) | M | Progress SSE events; parallel tool calls; optional deterministic `diffBills` pre-fetch; `effort` tuning; TTFT definition to be agreed (nfr.md) | Tech lead | 2, 5, 10 |
| R-13 | **LLM cost overrun** (longer conversations, low cache hit rate, extra round trips) | M | M | Cost-per-conversation metric and alert; max cost per conversation; token budgets; cache hit-rate monitoring (a silent cache regression doubles cost); monthly cost review | Product + SRE | 5, 9 |
| R-14 | **Model or prompt drift; model retirement** | M | M | Model names from config; versioned prompts; eval gate before any change; canary; track deprecation notices | Tech lead | 5, 11 |
| R-15 | **BSS availability, latency or data quality** (late CDRs, category mapping, missing opt-in evidence) | M | H | Gateway timeouts and circuit breakers; `BssUnavailableException` → honest "data unavailable" answer; contract tests (Pact); explicit "unknown opt-in → escalate" rule | Integration lead | 3, 10 |
| R-16 | **Excessive agency** (agent performs or promises actions outside scope) | L | H | Action tools only propose; autonomy flags; system prompt limits; the LLM never sees thresholds; supervisor review | Product | 5, 6 |
| R-17 | **Proactive batch misses the 6 h window** | L | M | KEDA on consumer lag; semaphore sized from the rate limit; lag alert; capacity sized at 3.7/s with pods to spare | SRE | 7, 9 |
| R-18 | **Spec–API conflicts and framework gaps** (temperature on Sonnet 5; Spring AI support for prompt caching and adaptive thinking) | H | M | Verify against the pinned Spring AI version before implementing (AGENTS.md); open questions Q-1 and Q-2 | Tech lead | 2, 5 |
| R-19 | **Provider lock-in** | M | L | Spring AI abstraction; Ollama profile for local; deterministic core works without any LLM | Architect | 2 |
| R-20 | **Operational adoption** (care agents or supervisors do not trust or use it) | M | M | Assisted mode with the same diagnosis; UAT scripts with the care team; feedback loop into evals | Product | 10, 12 |

### Ranking (highest combined exposure first)

1. **R-02 / R-01**: financial harm from wrong credits or amounts. Largely controlled by
   the design (deterministic engines, HITL, Level 0 launch), but the impact is severe.
2. **R-05**: data residency, which could change the provider route. Open until Phase 2.
3. **R-08 / R-09**: regulatory exposure. Needs legal review before launch.
4. **R-03**: prompt injection. High likelihood; mitigated by architecture.
5. **R-12**: latency definition and TTFT.
6. **R-07**: rate limits. Low at 1x with caching; high only at 10x or stress.
