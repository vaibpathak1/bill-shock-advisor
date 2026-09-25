# ADR-001: Modular monolith, two deployables

| | |
|---|---|
| Status | Accepted (SPEC §2.1); deployment detail per A-59 |
| Date | 2026-09-25 |
| Deciders | Repo owner |
| Related | [architecture.md](../architecture.md), [scalability.md](../scalability.md), ADR-006 |

## Context

Bill Shock Advisor has two workloads with very different shapes (capacity-estimates §3, §1):

| Workload | Shape | 1x load | Scaling signal |
|---|---|---|---|
| Live chat | Latency-sensitive, long-lived SSE streams (~10 s per turn), 3–8 tool calls per turn | 7.5 turns/s peak minute, ~75 streams in flight | CPU + active SSE connections |
| Proactive diagnosis | Throughput-sensitive, bursty (bill runs), LLM-bound | 278 `bill.generated`/s burst, 3.7 diagnoses/s for 6 h | Kafka consumer lag |

The team is small (A-45: 4 backend engineers; A-46: one engineer for the reference
implementation) and the domain is still evolving (bill-shock causes, guardrails and
tools will change after the pilot). Both workloads share the same domain model, engines
(`analysis`), gateways (`bss`) and audit.

## Options

| Option | For | Against |
|---|---|---|
| A. Single deployable | Simplest to run | A bill run competes with live chat for CPU, DB connections and LLM quota in the same pods; one scaling signal cannot serve both |
| **B. Modular monolith, two deployables** | One codebase and one domain model; module boundaries enforced at build time; each workload scales on its own signal | Discipline needed to keep modules apart; both deployables are released together |
| C. Microservices (per module) | Independent deploys and scaling per service | Network hops and distributed transactions around money and actions; heavy platform cost for a small team; boundaries not yet stable |
| D. Serverless (Lambda / Cloud Run jobs) | Scale to zero between bill runs | SSE streams last 10 s+ and conversations 8 min; JVM cold starts hurt NFR-01a (p95 < 1.5 s); per-invocation LLM concurrency control is harder |

## Decision

**Option B.** One Spring Boot codebase with Spring Modulith modules (SPEC §4.1), deployed
as two Kubernetes Deployments:

- **`chat-api`**: REST + SSE chat, actions, notifications, admin; `claude-sonnet-5`.
- **`proactive-worker`**: Kafka consumers for `bill.generated` and `bill.anomaly.detected`,
  the scheduled jobs (nightly usage feed load, partition maintenance and retention);
  the current Haiku-tier model (config).

**A-59:** both run from **one Maven artifact and one container image**. The runtime role is
chosen by Spring profile (`chat-api` or `proactive-worker`). Profile-conditional
configuration switches on only the entry points a role needs (web/SSE controllers vs Kafka
listeners and schedulers). Every module stays on the classpath, so shared engines have
exactly one version. Locally (`docker compose up`) a single process runs with both
profiles active and in-process events (ADR-006).

Module rules:
- Module dependencies are allowed only through each module's published API (the Modulith
  "named interfaces"). The allowed-dependency table is in architecture.md §4.
- A Modulith verification test (`ApplicationModules.of(...).verify()`) fails the build on
  cycles or on access to another module's internal packages (SPEC §4.1).
- Cross-module side effects go through application events (published via the Modulith
  event publication registry, which is the outbox; ADR-006), not direct calls.

## Consequences

- Positive: in-process calls between modules; ACID transactions for "write the action +
  its audit + its outbox event" in one database transaction (ADR-002, ADR-004).
- Positive: `chat-api` scales on CPU + SSE (HPA); `proactive-worker` scales on lag (KEDA).
  A bill run cannot starve chat pods. It also cannot starve chat's LLM quota, because the
  two roles use different models and API workspaces (scalability.md §4).
- Negative: both deployables are always released together, so a change to the worker ships
  with chat. The canary (SPEC 8.1) covers `chat-api`; the worker is rolled with
  `maxUnavailable: 0` and consumer-group rebalancing.
- Negative: a single image carries code the role doesn't use (larger image, larger
  attack surface). Mitigated with profile-conditional beans and NetworkPolicies per role.
- **Extraction path:** a module whose boundary proves stable and whose load profile
  diverges (most likely `proactive` or `bss` ingestion) can become its own service. Its
  events already go through Kafka in production, so consumers don't change.
