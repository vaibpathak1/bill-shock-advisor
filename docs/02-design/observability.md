# Observability

| | |
|---|---|
| Status | Draft for review (Phase 2) |
| Date | 2026-09-25 |
| Spec reference | SPEC.md §2.7, §2.4, §10 |
| Related | [nfr.md](nfr.md) (SLIs), [llm-architecture.md](llm-architecture.md) §12, [scalability.md](scalability.md), decision Q-4 |

Everything stays **in-region** (ap-south-1). Telemetry carries **no PII** (§4), so it could
leave the region, but the default is not to ship it anywhere else.

| Signal | Local (`docker compose`) | Production |
|---|---|---|
| Metrics | Micrometer → Prometheus → Grafana | Micrometer → in-cluster Prometheus (kube-prometheus-stack; long-term storage in S3 via Thanos or Amazon Managed Prometheus) → Grafana |
| Logs | JSON to stdout → Loki (via Grafana Alloy) | JSON to stdout → OpenSearch (in-region) |
| Traces | Micrometer Tracing (OpenTelemetry bridge) → OTLP → Tempo | OTLP → Tempo (S3 backend) |
| Dashboards / alerts | JSON dashboards and Prometheus rule files in the repo (`deploy/observability/`), provisioned automatically | Same files, via Helm |

---

## 1. SLIs and SLOs (from nfr.md; decision Q-4)

| SLI | Definition (PromQL sketch) | SLO / alert |
|---|---|---|
| **Available** (NFR-05) | `(chat turns completed OK + turns answered by template fallback) ÷ valid turns` — `sum(rate(chat_turns_total{outcome=~"ok|fallback"}[5m])) / sum(rate(chat_turns_total{outcome!="client_error"}[5m]))` | **99.9% monthly**. Multi-window burn-rate alerts: page on 14.4x over 1 h + 5 min; ticket on 6x over 6 h + 30 min |
| **Full capability** (Q-4) | Only LLM-answered turns count as success — `sum(rate(chat_turns_total{outcome="ok"}[1h])) / sum(rate(chat_turns_total{outcome!="client_error"}[1h]))` | **Alert when below 99.5% in any 1-hour window, even if "available" is green** |
| First meaningful content (NFR-01a) | `histogram_quantile(0.95, chat_first_content_seconds)` | p95 < 1.5 s; alert when > 1.5 s for 10 min |
| First LLM word (NFR-01b) | `chat_first_llm_token_seconds` (first **released** sentence, llm-architecture.md §7) | p95 < 8 s; alert when > 8 s for 10 min |
| Full response (NFR-02) | `chat_turn_duration_seconds` | p95 < 15 s; alert when > 15 s for 10 min |
| REST latency (NFR-03) | `http_server_requests_seconds` excluding `/chat` | p95 < 300 ms, p99 < 800 ms |
| Proactive window (NFR-04) | Time from the first `bill.generated` of the cycle day to the last diagnosis persisted — `proactive_cycle_day_completion_seconds` + the live `proactive_cycle_day_remaining` gauge | 6 h; alert if the projected completion (remaining ÷ current rate) > the time left |
| Fallback rate (NFR-15) | `rate(llm_fallback_total[1h]) / rate(chat_turns_total[1h])` | < 5% (SPEC §2.7 alert) |
| Prompt-cache share (NFR-21) | `sum(rate(llm_tokens_total{type="cache_read"}[1h])) / sum(rate(llm_tokens_total{type=~"input|cache_read|cache_write"}[1h]))` | ≥ 60%; alert on a sustained drop (30 min) |

## 2. Metrics catalogue

**From Spring AI 2.0.1** (observation names checked in the sources, 2026-09-25; unchanged from 1.1.8, llm-architecture.md F-8):
`gen_ai.client.operation` (timer; tags such as `gen_ai.request.model`,
`gen_ai.response.model`, `gen_ai.operation.name`) and `gen_ai.client.token.usage` (tag
`gen_ai.token.type`: input/output/total), plus tool-call observations (`spring.ai.tool`).
Spring AI's token metric **does not split cache reads and writes**, so the custom
`llm_tokens_total` below does, using the Anthropic usage fields (llm-architecture.md F-1).
**Prompt/completion and tool argument/result content capture in observations stays
disabled** in every environment except local debugging, because it would put customer
data into telemetry.

**Custom (Micrometer), low-cardinality tags only (never an account or conversation id):**

| Metric | Type | Tags | Purpose |
|---|---|---|---|
| `chat_turns_total` | counter | `outcome` (ok, fallback, guardrail_escalation, rate_limited, client_error, server_error), `prompt_version`, `autonomy_level` | SLIs |
| `chat_first_content_seconds`, `chat_first_llm_token_seconds`, `chat_turn_duration_seconds` | histogram | `prompt_version` | NFR-01/02 |
| `sse_active_connections` | gauge | — | HPA custom metric |
| `llm_calls_total` | counter | `model`, `client` (chat, proactive), `outcome` (ok, 429, 5xx, timeout, breaker_open) | Provider health |
| `llm_tokens_total` | counter | `model`, `type` (input, cache_write, cache_read, output) | Cost, cache share, rate-limit headroom |
| `llm_cost_usd_total` | counter | `model`, `client` | Cost per hour, per day |
| `conversation_cost_inr` | distribution summary | — | Cost per conversation vs threshold (NFR-09) |
| `llm_fallback_total` | counter | `reason` (unavailable, budget, grounding, global_limit, template_only_mode) | Fallback rate |
| `grounding_violation_total` | counter | `gate` (sentence, diagnosis, action_claim, prompt_leak) | Quality and safety |
| `tool_calls_total` | counter | `tool`, `outcome` | Tool errors and loops |
| `tool_calls_per_turn` | histogram | — | Guardrail at 8 |
| `diagnoses_total` | counter | `source` (proactive, chat), `primary_cause` | Diagnoses per minute |
| `diagnosis_cache_requests_total` | counter | `result` (hit_redis, hit_db, miss) | Cost-saver effectiveness |
| `actions_proposed_total`, `actions_confirmed_total`, `actions_rejected_total`, `actions_executed_total`, `actions_failed_total` | counter | `type`, `autonomy_level` | Proposed vs confirmed |
| `auto_credit_inr_total` | counter | — | **Auto-credits issued, ₹ per hour** (fraud/bug signal) |
| `credit_inr_total` | counter | `approval` (customer, supervisor, auto) | All credits |
| `proactive_lag_records` | gauge (from the Kafka exporter/KEDA) | `group` | Lag dashboard |
| `proactive_llm_permits_in_use` | gauge | — | Backpressure |
| `bss_calls_total`, `bss_call_seconds` | counter / timer | `gateway`, `outcome` | BSS health |
| `usage_reconciliation_mismatch_total` | counter | `category` | Data quality (ADR-007) |
| `usage_ingest_batches_total` | counter | `source`, `status` | Feed health, quarantines |
| `rate_limit_rejections_total` | counter | `limit` | Abuse / capacity |
| `feedback_total` | counter | `rating` | Negative-feedback rate (canary analysis, SPEC §8.1) |

Standard metrics also collected: JVM, HikariCP, HTTP server, Kafka client, Resilience4j,
Caffeine, Lettuce.

## 3. Logs

- **Format:** JSON to stdout using Spring Boot's built-in structured logging (Boot 3.4+,
  `logging.structured.format.console`; checked when the Boot version is pinned in 3a).
- **MDC fields on every line:** `correlationId` (from `traceparent` or generated),
  `conversationId`, `promptVersion`, `traceId`, `spanId`, `role` (chat-api or
  proactive-worker), plus `accountRef`: a **salted hash** of `account_id`, never the raw id.
- **What is never logged:** message content, tool arguments or results, MSISDN (masked if
  it appears at all), names, tokens, API keys. A Logback masking converter redacts
  patterns (phone, e-mail, card, Aadhaar-like) as a safety net (Phase 5b), and a CI test
  scans captured logs for PII patterns (security.md §10).
- **Levels:** INFO for business events (turn complete, action state change, batch loaded);
  WARN for degradations (fallback, BSS retry); ERROR only for failures needing attention.
- **Retention:** 30 days hot in Loki/OpenSearch (placeholder). The audit log (DB) is the
  long-term record, not application logs.

## 4. Traces

- **Instrumentation:** Micrometer Tracing + OpenTelemetry bridge; W3C `traceparent`
  propagation over HTTP and in **Kafka headers**, so a proactive trace covers
  `bill.generated → screening → bill.anomaly.detected → diagnosis → notification`.
- **Spans covering chat → LLM → tool → gateway → DB (SPEC §2.7):** HTTP server span →
  `chat.turn` → `diffBills.prefetch` → `gen_ai.client.operation` per LLM call →
  `spring.ai.tool` per tool → `bss.<gateway>` client span → JDBC spans (datasource-micrometer)
  → Redis spans.
- **Span attributes:** model, prompt version, token counts, tool name, outcome. **No
  content, no PII** (§2).
- **Sampling:** 100% locally; in production 10% head sampling plus **tail-keep of errors,
  fallbacks and turns > 15 s** (OTel collector tail sampling).

## 5. Dashboards (JSON in the repo)

| Dashboard | Panels |
|---|---|
| **Service Health** | Available and full-capability SLIs with the error budget; NFR-01a/01b/02/03 latency percentiles; request and error rate by endpoint; SSE connections and HPA replicas; JVM/GC; HikariCP pool; replica lag; Redis hit rate and memory; BSS gateway latency and errors; circuit-breaker states |
| **LLM Cost & Quality** | Tokens by model and type; cache-read share (NFR-21); cost per hour and per day in USD/INR; cost per conversation (p50/p95 vs ₹45); RPM/ITPM/OTPM vs the configured limits (NFR-22); 429/5xx/timeout rate; fallback rate by reason; grounding violations; tool calls per turn; latency per model; prompt version in use |
| **Business Outcomes** | Diagnoses per minute by cause; proposed vs confirmed vs executed actions; **auto-credits ₹/hour**; credits by approval path; escalations; containment proxy (conversations with no escalation); feedback up/down; proactive notifications and outreach acceptance (K-07); diagnosis cache hit rate |
| **Kafka Lag** | Lag per consumer group and partition; consume rate vs produce rate; KEDA replicas; LLM permits in use; DLQ depth per topic; projected completion of the cycle day vs the 6 h window (NFR-04); usage ingest batches and quarantines |

## 6. Alert rules (Prometheus rules in the repo)

All thresholds are config values in the rule files (placeholders, reviewed after the
pilot).

| Alert | Condition (sketch) | Severity | Runbook (Phase 12) |
|---|---|---|---|
| `ChatAvailabilityBurnRate` | Available SLI burn rate 14.4x (1 h & 5 m) | page | Service degradation |
| `ChatFullCapabilityLow` | Full-capability SLI < 99.5% over 1 h (Q-4) | page (business hours) / ticket | LLM provider outage |
| `ChatErrorRateHigh` | 5xx rate > 1% for 5 min | page | Service degradation |
| `ChatLatencyP95High` | NFR-01a/01b/02 p95 above target for 10 min | ticket | Latency |
| `RestLatencyHigh` | p95 > 300 ms or p99 > 800 ms for 10 min | ticket | Latency |
| `LlmFallbackRateHigh` | fallback rate > 5% over 1 h (SPEC §2.7) | page | LLM provider outage |
| `LlmCacheShareDrop` | cache-read share < 60% for 30 min | ticket | Cost spike |
| `LlmCostPerHourHigh` | `llm_cost_usd_total` hourly increase > 1.5x the same hour last week, or > the absolute cap | page | Cost spike |
| `ConversationCostHigh` | p95 conversation cost > ₹45 for 30 min | ticket | Cost spike |
| `AutoCreditRateHigh` | `increase(auto_credit_inr_total[1h])` > ₹ threshold (config; placeholder ₹50,000/h at Level 2) | **page + automatic kill switch to Level 1** | Wrong or excessive credits |
| `CreditAnomaly` | Credits per hour (any approval path) > 3x the 7-day baseline | page | Wrong or excessive credits |
| `GroundingViolations` | > 0.5% of turns over 1 h | ticket | Prompt regression |
| `PromptLeakAttempts` | `grounding_violation_total{gate="prompt_leak"}` > 10 in 10 min | ticket | Suspected prompt-injection attack |
| `ProactiveLagGrowing` | lag rising for 15 min **and** projected completion > time left in the 6 h window | page | Kafka lag growing |
| `DlqNotEmpty` | any DLQ depth > 0 for 15 min | ticket | Kafka lag growing |
| `ReplicaLagHigh` | replica lag > 30 s for 5 min | ticket | DB failover |
| `DbWalArchiveLag` | WAL archiving lag > 5 min (RPO guard, NFR-06) | page | DB failover |
| `UsageIngestQuarantine` | any batch `QUARANTINED` | ticket | Data quality |
| `ReconciliationMismatch` | mismatches > 0.1% of rolled-up bills per cycle day | ticket | Data quality |
| `ProviderRateLimitHeadroom` | provider usage > 70% of the configured limit at the peak minute (NFR-22) | ticket | Capacity |
| `PartitionMissing` | the next month's partition does not exist 7 days before month end | page | DB maintenance |

The auto-credit alert is the one alert that **acts by itself**: it lowers the autonomy
level to 1, so no further auto-approvals happen until a human reviews (ADR-004, R-02).

## 7. Cost per conversation and cost reviews

- `conversation.llm_cost_usd` (DB) is exact per conversation. `conversation_cost_inr`
  (metrics) gives the distribution; `llm_call_log` supports the monthly review (SPEC
  §10): tokens by model, cost per resolved case (K-05), cache hit rate, spend vs the tier
  cap.
- Cost per resolved case = LLM cost of conversations with no escalation ÷ their count.
  It is computed in the monthly job (it needs outcome data, not a real-time metric).
