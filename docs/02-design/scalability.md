# Scalability Design

| | |
|---|---|
| Status | Draft for review (Phase 2) |
| Date | 2026-09-25 |
| Spec reference | SPEC.md §2.3, §1.4, §5 (performance, scalability) |
| Related | ADR-001, ADR-006, [../01-requirements/capacity-estimates.md](../01-requirements/capacity-estimates.md), [nfr.md](nfr.md) NFR-10 to NFR-13, [data-architecture.md](data-architecture.md) §6 and §9 |

Load points (capacity-estimates): chat **7.5 turns/s** at the 1x peak minute, 75 turns/s
at 10x, 40 turns/s as the stress case; proactive **3.7 diagnoses/s** (80k in 6 h) at 1x,
37/s at 10x; `bill.generated` burst **278/s** at 1x.

---

## 1. Horizontal scaling

Both deployables are **stateless** (ADR-001): chat memory, actions and idempotency live
in PostgreSQL; caches in Redis are rebuildable. Any pod can serve any turn, so no sticky
sessions are needed.

| | `chat-api` | `proactive-worker` |
|---|---|---|
| Pod size | 2 vCPU / 4 GiB (A-31), Java 21 virtual threads | 2 vCPU / 4 GiB |
| Autoscaler | **HPA**: CPU 60% **and** active SSE connections per pod (custom metric `sse_active_connections` via Prometheus Adapter, target 300 of ~500 capacity). The higher recommendation wins | **KEDA** `ScaledObject` with Kafka lag triggers for the two consumer groups (§3) |
| Min / max replicas | **3** (99.9% HA floor, spread over 3 AZs) / 12 | **1** / computed ceiling (§4): max = global LLM concurrency ÷ per-pod semaphore |
| Replicas needed (capacity §9) | 1x: 3 · 3-yr: 3 · 10x: 7 · stress: 4 | 1x: 2 · 3-yr: 3 · 10x: 15 |
| Scale-out time target | ≤ 5 min to target replicas (NFR-12, proposed) | ≤ 5 min |
| Disruption | PDB `minAvailable: 2`; topology spread across AZs | PDB `maxUnavailable: 1`; cooperative-sticky rebalancing |
| Shutdown | `preStop` sleep 10 s (drain from the ALB), Spring graceful shutdown 45 s, `terminationGracePeriodSeconds: 60`, so in-flight turns (p95 < 15 s) finish | Stop polling, finish in-flight records, commit offsets; grace 60 s |

**HPA behaviour:** scale up fast (100% per 60 s, stabilisation 0 s), scale down slowly
(stabilisation 300 s). Bill-shock chat ramps within minutes of a bill run's notifications.

**Database connections:** HikariCP per pod: `chat-api` 20, worker 20. At 10x: 7 × 20 + 15 × 20
= 440 connections, within a large RDS instance's limits. RDS Proxy is the option if
connection churn becomes a problem. With virtual threads, the pool (not the thread count)
limits concurrent DB work, which is intended.

## 2. Load balancing and SSE

- **L7 ingress:** AWS ALB (via the AWS Load Balancer Controller) behind WAF. Round-robin; no
  stickiness.
- **SSE-friendly settings:**
  - ALB idle timeout **300 s** (the default 60 s would cut slow tool rounds);
  - the server sends an SSE comment heartbeat every **15 s**;
  - response buffering off (`Cache-Control: no-cache`; `X-Accel-Buffering: no` if an NGINX
    tier is added);
  - HTTP/2 to clients.
- **Reconnects:** a turn is persisted server-side as it completes. The client sends a
  `clientMessageId`. A retry with the same id does not start a second turn: it returns the
  stored result. **API addition (decision Q-16, implemented in 5b):** `GET
  /api/v1/chat/{conversationId}/messages` lets the UI reload a conversation after a dropped
  stream. The design is in architecture.md §11. The MVP slice has no stream recovery.
- **Locally:** no ALB; Spring serves SSE directly.

## 3. Asynchronous processing via Kafka (ADR-006)

**Topics** (MSK: 3 brokers over 3 AZs, RF 3, `min.insync.replicas=2`; producers
`acks=all`, `enable.idempotence=true`):

| Topic | Producer | Consumer group(s) | Key | Partitions (1x → 10x) | Retention | Retries | DLQ |
|---|---|---|---|---|---|---|---|
| `bill.generated` | BSS integration adapter (TMF678 notifications) | `proactive-screening` (worker): store bill + line items, roll up usage, run `AnomalyDetector` | `account_id` | 24 → 48 | 7 days | 3 in-place, backoff 1 s / 4 s / 16 s | `bill.generated.dlq` |
| `bill.adjusted` (**A-62**) | BSS integration adapter | `proactive-adjustments` (worker): refresh the bill, re-run the roll-up, **evict caches**; `chat-cache-evict` (chat-api, broadcast group per pod) | `account_id` | 12 | 7 days | 3 in-place | `bill.adjusted.dlq` |
| `bill.anomaly.detected` | worker (outbox) | `proactive-diagnosis` (worker): diagnosis + Haiku explanation + notification | `account_id` | 12 → 48 | 7 days | 3 in-place for transient errors. **LLM unavailable → template text, not DLQ** | `bill.anomaly.detected.dlq` |
| `notification.created` | worker (outbox) | notification delivery adapter (in-app/push; A-39) | `account_id` | 12 | 7 days | 5 with backoff | `notification.created.dlq` |
| `action.executed` | chat-api (outbox) | audit/analytics consumers; cache eviction; future CRM sync | `account_id` | 12 | 7 days | 3 | `action.executed.dlq` |
| `*.dlq` | error handler | ops tooling (inspect, replay after a fix) | original key | 3 | 14 days | — | — |

- **Two stages for proactive** (`bill.generated` → screening → `bill.anomaly.detected` →
  diagnosis). Screening is cheap and runs at 278/s bursts; diagnosis is LLM-bound at
  3.7/s. Each consumer group has its own lag and its own KEDA trigger, so a backlog of
  diagnoses never slows screening.
- **Error handling:** Spring Kafka `DefaultErrorHandler` with a `DeadLetterPublishingRecoverer`.
  Non-retryable errors (validation, unknown account) go straight to the DLQ. Transient
  errors (BSS timeouts, DB failover) retry in place with backoff. DLQ records keep the
  original headers plus the exception class and message (no PII).
- **Idempotent consumers:** `processed_event` + natural keys (ADR-006).
- **Consumer tuning:** diagnosis `max.poll.records=20` (bounded by the LLM semaphore);
  screening `max.poll.records=200` with batch inserts.
- **KEDA triggers:** `proactive-screening` lag threshold 5,000 per replica;
  `proactive-diagnosis` lag threshold 300 per replica. Draining 80k diagnoses in 6 h needs
  3.7/s; 2 pods × 20 permits ÷ 8 s ≈ 5/s gives headroom (capacity §9).

## 4. Backpressure and LLM quotas

- **Separate quotas** (SPEC §2.3): chat and proactive use different models (separate
  provider rate-limit buckets, A-21) **and** different workspaces with their own API keys
  and spend limits (llm-architecture.md §1).
- **Proactive global ceiling:** the workspace limit is set to a share of the Haiku limit,
  for example **4,000 RPM** of the Scale tier's 10,000 (config). Concurrency ceiling = RPM ×
  latency ÷ 60 = 4,000 × 8 s ÷ 60 ≈ **533 in-flight calls**. The per-pod semaphore is
  **20** (A-32), so the KEDA `maxReplicaCount` is **26**. Scaling beyond the quota would
  only produce 429s.
- **Adaptive limiting:** on a 429 the pod honours `retry-after` and halves its permits
  (AIMD), then recovers by +1 every 10 s of success. A shared Redis token bucket (Bucket4j)
  for proactive RPM smooths bursts across pods.
- **Kafka backpressure:** when all permits are taken, the listener container **pauses** its
  partitions instead of buffering records in memory, and resumes when permits free up.
- **Chat:** per-pod limits come from CPU/SSE; the global limit is the chat rate limit
  (§5).

## 5. Rate limiting (Bucket4j backed by Redis)

| Limit | Default (config) | Scope | Over the limit |
|---|---|---|---|
| Chat turns per customer | **20 per hour**, burst 5 per minute (SPEC §2.3) | `account_id` | 429 Problem Details with `Retry-After` and a plain message |
| Confirm/reject per customer | 10 per minute | `account_id` | 429 |
| **Global chat turns** | **12 turns/s** at the Scale tier: keeps Sonnet counted ITPM ≤ 70% (10 M × 70% ÷ (3 calls × 12,000 × 25%) ≈ 13 turns/s). Raised with negotiated limits | cluster | **Graceful degradation:** answer with the deterministic summary and template (no LLM), not a hard error. Counted in the fallback rate |
| Proactive LLM RPM | 4,000 (workspace share) | cluster | Pause consumption (§4) |
| Admin / supervisor APIs | 60 per minute per user | user | 429 |
| Conversation message reads (`GET …/messages`, 5b) | 60 per minute | `account_id` | 429 |

Bucket state lives in Redis (Lettuce-based proxy manager). If Redis is unavailable, the
limiter **fails open for per-customer limits** (availability) but **keeps a local per-pod
approximation of the global limit** (cost protection). The chaos test covers this (SPEC
§5).

## 6. Caching (multi-level)

| Level | Content | Key | TTL | Invalidation |
|---|---|---|---|---|
| L1 Caffeine (per pod) | Plan catalogue, tariff rates, add-ons | `catalog:{version}` | 10 min (SPEC) | Daily catalogue refresh publishes an evict message (Redis pub/sub) |
| L1 | Policy config (guardrail thresholds) | `policy:{key}` | 10 min | Admin change → pub/sub evict |
| L1 | **Feature flags** (autonomy level, kill switches, LLM mode) | `flag:{key}` | **5 s** | pub/sub evict on change, so the kill switch acts on the next turn (NFR-20) |
| L2 Redis | Bill summary | `bill:sum:{accountId}:{period}` | 15 min (SPEC) | `bill.adjusted` → delete |
| L2 Redis | **Diagnosis per bill** (the biggest cost saver, SPEC §2.3) | `diag:{billId}:{engineVersion}` | 35 days (A-48) | `bill.adjusted` → delete; a new engine version misses automatically |
| L2 Redis | Per-session usage detail from BSS | `udetail:{conversationId}:{period}:{type}` | 2 h (A-25) | TTL only |
| L2 Redis | Active subscriptions (BSS) | `subs:{accountId}` | 15 min | `action.executed` (VAS unsubscribe, plan change) → delete |

- **Diagnosis reuse:** the proactive flow computes the diagnosis once, stores it in
  `bill_shock_diagnosis` (the source of truth) and in Redis. The chat pre-fetch reads Redis
  → DB → computes, in that order (A-22: 60% of chats about flagged bills hit).
- **Stampede protection:** single-flight per key. A Redis `SET NX` lock (10 s) is held while
  computing a diagnosis; other callers wait up to 2 s and then read the result.
- **Redis sizing:** capacity §8, ≈ 4 GB at 1x and ≈ 37 GB at 10x (cluster mode).
  `maxmemory-policy allkeys-lru` for cache keys. Rate-limit buckets live in a separate
  logical database with `noeviction`.

## 7. Scheduled jobs

Run in `proactive-worker`, each guarded by a PostgreSQL advisory lock so only one pod runs
it (no extra library):

| Job | Schedule | Notes |
|---|---|---|
| Nightly usage feed load (A-24, A-63) | 00:30–04:30 IST window | Batch-id idempotent (ADR-007) |
| Partition maintenance and retention | Daily 05:00 | data-architecture.md §5, §10 |
| Outbox purge (completed publications > 7 days) | Hourly | ADR-006 |
| `processed_event` purge (> 14 days) | Daily | |
| Proposed-action expiry | Every 5 min | `PENDING_CONFIRMATION` past `expires_at` → `EXPIRED` |
| Audit digest to S3 | Daily 01:00 | security.md §8 |

## 8. Path to 10x

| Component | 1x | 10x | How it scales |
|---|---|---|---|
| `chat-api` | 3 pods | 7 pods | HPA; no code change |
| `proactive-worker` | 2 pods | 15 pods (or 6 with semaphore 50, if the quota allows) | KEDA; partitions 24 → 48 |
| PostgreSQL | 0.82 TB, ~1.1k IOPS, 1 replica | 8.2 TB, ~10.7k IOPS, 1–2 replicas | Vertical + replicas; sharding path ready but not needed (data-architecture.md §8) |
| Redis | 4 GB | 37 GB | Cluster mode |
| Kafka | ~300 msg/s | ~3,000 msg/s | Same 3 brokers; more partitions |
| LLM (Sonnet 5) | 41% of Scale ITPM | 405% | **Custom limits needed** (capacity §4.6), negotiated 6 months ahead |
| LLM (Haiku-tier model) | 9% | 89% | Custom limits or Batch API |

The 10x test (SPEC §5) runs with the **stubbed LLM with latency injection**, because the
real provider's limits would be exceeded (capacity §4.3).
