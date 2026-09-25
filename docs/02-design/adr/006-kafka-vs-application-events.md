# ADR-006: Kafka for events versus Spring ApplicationEvent

| | |
|---|---|
| Status | Accepted (SPEC §2.1); outbox mechanism proposed here |
| Date | 2026-09-25 |
| Deciders | Repo owner |
| Related | [scalability.md](../scalability.md) §3, [architecture.md](../architecture.md) §5, ADR-001 |

## Context

SPEC §2.3 defines the topics `bill.generated`, `bill.anomaly.detected`, `action.executed`
and `notification.created`, keyed by `account_id`, each with retries and a DLQ. SPEC §4.2
requires a **Transactional Outbox** for reliable publishing and **Idempotent Consumers**.
SPEC §2.1 requires an abstraction: in-process events for local development, Kafka in
staging and production. The whole system must run with `docker compose up` (SPEC
guiding principle), and the MVP slice's compose file has PostgreSQL only
(plan-and-budget §2a).

Load (capacity §7): at most ~300 msg/s at 1x and ~3,000 msg/s at 10x. Bandwidth is tiny;
partitions exist for consumer parallelism.

## Options

| Option | For | Against |
|---|---|---|
| A. Spring `ApplicationEvent` only | Simple; no broker | Events are lost on crash (after commit, before handling); no cross-process delivery between the two deployables; no replay |
| B. Kafka everywhere, including local | One code path | Heavier local setup; the MVP compose has no Kafka; slower tests |
| **C. Domain code publishes application events; Spring Modulith's Event Publication Registry is the outbox; externalization to Kafka is switched on by profile** | One publishing API; outbox semantics built in; in-process locally, Kafka in staging/prod | Consumers need two adapters (in-process listener locally, `@KafkaListener` in prod) calling the same handler |
| D. Hand-rolled outbox table + poller (or Debezium CDC) | Full control; CDC gives low latency | More code to own (C covers the need); Debezium is another component to run |

## Decision

**Option C**, using Spring Modulith 1.4.x (the line compatible with Spring Boot 3.5;
**version pinned in Phase 3a**, with the API checked against the pinned sources as
AGENTS.md requires):

- **Publishing:** a module publishes a domain event with `ApplicationEventPublisher` inside
  its business transaction. The Modulith **Event Publication Registry** (JDBC; the
  `event_publication` table) records the publication in the same transaction. That is
  the Transactional Outbox.
- **Externalization:** event types that cross deployables are annotated
  `@Externalized("<topic>::#{accountId()}")`, which gives the topic and the key (`account_id`,
  SPEC §2.3). With the `spring-modulith-events-kafka` module on the classpath and
  externalization enabled (staging/prod profiles), the registry publishes them to Kafka
  and marks them completed after the send is acknowledged. Checked in the 1.4.13 sources:
  `org.springframework.modulith.events.Externalized`,
  `EventExternalizationConfiguration`, `KafkaEventExternalizerConfiguration`.
- **Local profile:** externalization off; the same events reach
  `@ApplicationModuleListener` handlers in-process (asynchronously, after commit),
  still recorded in the registry. Incomplete publications are re-submitted on restart.
- **Consuming in staging/prod:** thin `@KafkaListener` adapters call the same handler
  services the in-process listeners call. A handler never knows which transport
  delivered the event.
- **Idempotent consumer** (SPEC §4.2): every event carries an `eventId` (UUID). Handlers
  record `(consumer_name, event_id)` in `processed_event` in the same transaction as their
  side effects, and a duplicate is skipped. Natural keys add a second layer: one diagnosis
  per `(bill_id, engine_version)`, one notification per `(bill_id, type)`.
- **BSS-originated events** (`bill.generated`, `bill.adjusted`) are produced by the BSS
  integration adapter, not by our domain (architecture.md §5). Locally, the admin
  endpoint `POST /api/v1/admin/simulate-bill-run` publishes them in-process.

Topics, partitions, retries and DLQs are specified in scalability.md §3.

## Consequences

- Positive: no lost events (outbox); the same business code runs locally without Kafka.
- Positive: replay: Kafka retention (7 days) plus the registry's completed publications
  support reprocessing a cycle day.
- Negative: delivery is **at least once**, so every consumer must be idempotent (enforced
  by the `processed_event` table and natural keys, and tested in Phase 7).
- Negative: event ordering is guaranteed only per partition (`account_id`). Handlers must
  not assume ordering across accounts. For one account, `bill.adjusted` after
  `bill.generated` is ordered because both use the same key.
- Negative: the `event_publication` table grows. Completed publications are purged after
  7 days by the maintenance job.
- Risk: externalization depends on Spring Modulith behaviour in the pinned version. If
  Phase 7 finds a gap (for example, header or key handling), the fall-back is option D
  (hand-rolled outbox poller). Handlers and payloads stay the same.
