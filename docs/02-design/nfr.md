# Non-Functional Requirements

| | |
|---|---|
| Status | Draft for review (written in Phase 1, per decision Q1) |
| Date | 2026-09-25 |
| Spec reference | SPEC.md §2.4, §1.4, §5, §7 |
| Inputs | [../01-requirements/capacity-estimates.md](../01-requirements/capacity-estimates.md), [../01-requirements/feasibility.md](../01-requirements/feasibility.md), [../01-requirements/assumptions.md](../01-requirements/assumptions.md) |

For each NFR: the target from the SPEC, the exact SLI (how it is measured), where it is
verified, and the alert that protects it in production. Items marked **PROPOSED** go
beyond the SPEC and need approval.

## 1. SPEC NFRs (SPEC §2.4)

| ID | NFR | Target | SLI: how it is measured | Verified in | Production alert (Phase 9) |
|---|---|---|---|---|---|
| NFR-01a | Chat first meaningful content | **p95 < 1.5 s** (design goal < 1 s) | Server-side: `POST /api/v1/chat` accepted → first SSE event carrying the deterministic spike summary (amount + top driver, SPEC 4.6). A bare "typing…" status does **not** count | Gatling (Phase 10); integration test in Phase 5 | p95 > 1.5 s for 10 min |
| NFR-01b | Chat first LLM-generated word | **p95 < 8 s** | Request accepted → first SSE text token produced by the LLM | Gatling with stubbed LLM + latency injection (Phase 10); small real-LLM latency test | p95 > 8 s for 10 min |
| NFR-02 | Chat full response (with tools) | **p95 < 15 s** | Request accepted → final SSE `done` event, for all turns including tool rounds | Gatling (Phase 10); real-LLM latency test | p95 > 15 s for 10 min |
| NFR-03 | Non-LLM REST APIs | **p95 < 300 ms, p99 < 800 ms** | Server-side latency per endpoint, excluding `/chat`. Diagnosis endpoint measured on a cache hit; a cache miss is reported separately | Gatling (Phase 10) | p95 > 300 ms or p99 > 800 ms for 10 min |
| NFR-04 | Proactive batch | **All flagged bills of a cycle diagnosed within 6 h** | Time from the **first** `bill.generated` event of a cycle day (decision Q-5) → last diagnosis persisted for that day's flagged bills | Synthetic bill-run at 1x (80k flagged) and 10x (Phase 10, stubbed LLM) | Consumer lag growth; projected completion > 6 h |
| NFR-05 | Availability (`chat-api`) | **99.9% monthly** (error budget ≈ 43.2 min/month) on SLI **"available"** | Two SLIs, both reported. **"Available"** = successful responses ÷ valid requests, where a deterministic template-fallback answer counts as success (5xx and timeouts are failures). **"Full capability"** = responses answered by the LLM ÷ valid requests (a fallback counts as a failure) | Chaos tests (Phase 10); staging soak | Burn-rate alerts on the "available" error budget. **Separate alert: "full capability" < 99.5% in any 1-hour window, even while "available" is green** |
| NFR-06 | RPO / RTO | **RPO ≤ 15 min, RTO ≤ 4 h** | Measured in the backup/restore drill and the DR drill (SPEC 7) | Staging drills (Phase 12) | WAL archiving lag > 5 min; failed snapshot |
| NFR-07 | Diagnosis accuracy | **≥ 95% correct primary cause** on the eval set | LLM evals (`@Tag("llm")`), primary cause compared with the scenario label; report in `build/eval-report.json` | `./mvnw verify -Pllm-evals` (only on request); staging eval gate blocks deploy | Negative-feedback rate; weekly eval of new failure cases (SPEC 10) |
| NFR-08 | False positives on normal bills | **0 invented issues** in the eval set | Scenario 6 and all normal-bill eval cases produce no anomalous cause and no proposed action | Eval gate (Phase 10) | — (quality gate) |
| NFR-09 | Cost | **Configurable max cost per conversation; alert above it** | Σ (tokens × configured price) per conversation, computed from usage fields. Starting threshold ₹45 (feasibility §3.2) | Metrics test (Phase 9) | Cost per conversation > threshold; cost per hour > threshold |

## 2. Throughput and scalability targets (derived from capacity-estimates.md)

| ID | Target | 1x | 10x headroom test | Stress |
|---|---|---|---|---|
| NFR-10 | Chat turns sustained with NFR-01/02/03 met | 7.5 turns/s peak minute; 400 concurrent conversations | 75 turns/s; 4,000 concurrent conversations; NFRs hold while HPA scales out | 40 turns/s; 3,000 concurrent sessions (SPEC 1.4). Find the breaking point; no NFR commitment |
| NFR-11 | Proactive throughput | 3.7 diagnoses/s sustained (80k in 6 h) | 37 /s (800k in 6 h) with KEDA scale-out | — |
| NFR-12 | Horizontal scaling | Both deployables stateless; scale-out without sticky sessions | HPA (`chat-api`) and KEDA (`proactive-worker`) reach target replicas within 5 min (**PROPOSED** time) | — |
| NFR-13 | Soak | 8 h at 1x with no memory growth or error-rate drift (SPEC 5) | — | — |

All load tests use the **stubbed LLM with realistic latency injection** (SPEC 5). Provider
limits at 10x and stress exceed the Scale tier (capacity §4.3).

## 3. Additional NFRs (PROPOSED, for approval)

These restate SPEC rules as measurable requirements so later phases can test them.

| ID | NFR | Target | Verified in |
|---|---|---|---|
| NFR-14 | Deterministic fallback latency | When the LLM is unavailable, the template response is delivered with p95 < 2 s | Chaos test: LLM 429/timeout (Phase 10) |
| NFR-15 | LLM fallback rate | < 5% of turns over 1 h (the SPEC 2.7 alert threshold) | Metrics (Phase 9) |
| NFR-16 | Monetary correctness | 100% of ₹ amounts shown to users equal engine output (BigDecimal, HALF_EVEN, 2 dp); zero LLM-computed amounts | Unit tests with exact BigDecimal assertions (Phase 4); grounding evals |
| NFR-17 | Action idempotency | Repeating a confirm with the same `Idempotency-Key` never executes twice; 100% under concurrent retries | Integration test (Phase 6) |
| NFR-18 | Audit completeness | 100% of tool calls, proposals, confirmations and executions have an audit record | Integration test (Phase 6) |
| NFR-19 | PII minimisation | LLM prompts contain no full MSISDN, name or address; logs contain no unmasked PII | Unit tests on masking; log scan in CI (Phase 5/8) |
| NFR-20 | Autonomy kill switch | Dropping the autonomy level takes effect on the next turn, with no redeploy | Integration test (Phase 6) |
| NFR-21 | Prompt-cache effectiveness | Cache-read share of chat input tokens ≥ 60% (A-16 assumes 75%); alert on a sustained drop | Metrics (Phase 9) |
| NFR-22 | Rate-limit headroom | Peak-minute provider usage ≤ 70% of the negotiated limit (capacity §4.3) | Provider-usage dashboard (Phase 9) |

## 4. Open questions on NFRs

| # | Question | Proposal |
|---|---|---|
| Q-3 | ~~What counts as the "first token"?~~ **Resolved 2026-09-25:** `diffBills` is pre-fetched in code and a deterministic spike summary is streamed first. Targets: first meaningful content p95 < 1.5 s (NFR-01a), first LLM-generated word p95 < 8 s (NFR-01b). SPEC 2.4 and 4.6 updated | — |
| Q-4 | ~~Does a fallback answer count as available?~~ **Resolved 2026-09-25:** yes for the "available" SLI, and a second "full capability" SLI is reported, with an alert below 99.5% in any hour (NFR-05) | — |
| Q-5 | ~~Where does the 6 h window start?~~ **Resolved 2026-09-25:** at the first `bill.generated` event of the cycle day (NFR-04) | — |
