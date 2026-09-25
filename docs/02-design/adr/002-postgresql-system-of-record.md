# ADR-002: PostgreSQL as the system of record

| | |
|---|---|
| Status | Accepted (SPEC §2.2, §3) |
| Date | 2026-09-25 |
| Deciders | Repo owner |
| Related | [data-architecture.md](../data-architecture.md), ADR-004, ADR-007 |

## Context

The application owns data where correctness matters more than write throughput:
proposed actions and their state machine, idempotency records, the audit trail, goodwill
credits, and diagnoses that are reused across flows. It also keeps local copies of BSS
data (bills, line items, usage aggregates; ADR-007) that the engines read by
`account_id` + `bill_period`. It needs vector search for policy RAG (SPEC §2.6).

Load at 1x (capacity-estimates, re-derived in data-architecture.md §9): about **0.8 TB**
in the primary and **~1,070 peak IOPS**. At 10x: about **8 TB** and **~10,700 IOPS**.

## Options

| Option | For | Against |
|---|---|---|
| **A. PostgreSQL 16 (RDS Multi-AZ) + pgvector** | ACID transactions over action + audit + outbox; NUMERIC for money; declarative partitioning with cheap `DROP PARTITION` retention; pgvector for 384-dim embeddings; strong Spring/Flyway support; a well-known sharding path (Citus / app-level) | A single primary is a write ceiling; vertical scaling first |
| B. DynamoDB / Cassandra (wide-column) | Horizontal write scale; TTL-based retention | No multi-item ACID across action + audit + outbox without extra machinery; no NUMERIC type (money as strings or scaled integers); the query paths (history ranges, supervisor queries) need secondary tables; a separate vector store needed |
| C. MongoDB | Flexible documents for diagnoses and tool payloads | Money needs Decimal128 discipline; multi-document transactions work but add operational care; a separate vector search setup |
| D. PostgreSQL for money and actions + NoSQL for usage | Scale usage separately | Two stores to operate; option C in ADR-007 shrinks usage to ~0.25 TB, so the NoSQL half is not justified |

## Decision

**Option A.** PostgreSQL 16 with pgvector is the system of record for everything the
application owns. BSS remains the system of record for billing and usage. Our copies are
read models with fixed retention.

Rules that follow:
- Money is `NUMERIC(14,2)`; never float (SPEC §2.2). In Java it is `BigDecimal` with
  HALF_EVEN, 2 dp (ADR-003). LLM cost metering uses `NUMERIC(14,6)` USD, because
  per-call costs are fractions of a cent (data-architecture.md §3).
- Money-changing transitions (confirm, execute, credit) run in one transaction with
  their audit row and their outbox event (ADR-004, ADR-006).
- Flyway owns the schema; migrations run as a pre-deploy Job (SPEC §6), with
  expand–contract for zero downtime.
- Read-only tool queries and history go to a read replica through a routing
  `DataSource`, with the exceptions in data-architecture.md §6 (Q-8).
- Large, time-bound tables are range-partitioned by month (data-architecture.md §5).

## Consequences

- Positive: one transactional store for actions and audit; no dual-write between stores.
- Positive: after ADR-007 the 10x load (~8 TB, ~10.7k IOPS) fits one large instance, so
  sharding is a documented path, not a v1 need (data-architecture.md §8).
- Negative: the write ceiling of a single primary. Watched with the sharding trigger
  metrics (data-architecture.md §8).
- Negative: partitioned tables need the partition key in every primary key and unique
  constraint. The schema design accounts for it (for example, `bill` has primary key
  `(bill_period, bill_id)`).
