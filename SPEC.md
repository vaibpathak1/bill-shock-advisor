# Bill Shock Advisor — Full SDLC Build Specification

## HOW TO USE THIS SPEC (instructions for the coding agent)
You are a senior Java/Spring engineer, solutions architect, and DevOps/SRE engineer with
deep telecom BSS experience. Build **Bill Shock Advisor**, an agentic AI system that
investigates unexpected spikes in a telecom customer's bill, explains the root cause,
proposes tailored resolutions, and executes approved actions within guardrails.

Guiding principle: **Design for production scale, build an MVP that runs locally.**
Every production concern (Kubernetes, Terraform, Kafka, DR) must be designed, documented,
and scaffolded, but the whole system must also run with `docker compose up` on a laptop.

Work phase by phase (Section 11). Write the design documents under `/docs` BEFORE the
code for each phase. Stop at each phase gate, summarize what was done, list open
questions, and wait for approval. Anything you cannot know (budget, real volumes,
regulatory interpretation) goes into the docs as an explicit **ASSUMPTION** to be
validated by humans. Do not present assumptions as facts.

---

## 1. PLANNING & REQUIREMENTS

### 1.1 Problem statement
"Bill shock" is a bill much higher than usual (e.g., 3x) caused by international roaming,
data overage, third-party/VAS subscriptions, mid-cycle plan changes, or billing errors.
It drives call-center volume, disputes, churn, and regulatory complaints. Chatbots today
only answer FAQs. This agent will:
1. INVESTIGATE: pull current and historical bills, usage records, and subscriptions.
2. DIAGNOSE: identify and quantify each driver of the spike.
3. RECOMMEND: re-rate actual usage against the plan catalog and show the exact savings.
4. RESOLVE: execute approved actions (credit, VAS unsubscribe, plan change, dispute).
5. PREVENT: detect anomalies at bill generation and reach out proactively.

### 1.2 Deliverable: `docs/01-requirements/PRD.md`
- Personas: postpaid consumer, care agent (assisted mode), billing ops supervisor.
- User stories with acceptance criteria for each of the 5 capabilities above.
- Out of scope for v1: prepaid, enterprise accounts, voice channel, multilingual
  (design the prompt/templates so Hindi can be added later).
- Business KPIs: % of bill-shock contacts resolved without a human, reduction in
  average handling time, dispute rate, CSAT, and cost per resolved case.

### 1.3 Deliverable: `docs/01-requirements/feasibility.md`
- Technical feasibility: data availability via BSS APIs, LLM tool-calling reliability,
  latency.
- Financial feasibility: model the LLM cost per conversation (input and output tokens ×
  price, with prices read from config, not hardcoded) plus infrastructure cost. Compare it
  with the cost of a human care interaction (ASSUMPTION: a configurable value).
- Risks: hallucinated amounts, wrong credits, prompt injection, data residency, LLM
  provider outage, regulatory exposure. Give each risk a mitigation.

### 1.4 Deliverable: `docs/01-requirements/capacity-estimates.md`
Use these ASSUMPTIONS (all configurable) and show the calculation:
- 10M postpaid subscribers, bills spread across 5 bill-cycle dates → about 2M bills per
  cycle day.
- 4% of bills flagged as anomalous → about 80k proactive diagnoses per cycle day.
- Live chat peak on the 2 days after a cycle: 3,000 concurrent sessions, about 40
  chat turns/sec, with 3–8 tool calls per turn.
- Data volume: 6 months of history per subscriber; estimate usage-record rows, audit rows
  per month, chat memory size, and storage growth over 3 years.
- Growth: 20% YoY subscriber growth, plus a 10x headroom test target.
- Derive from these: LLM tokens per day, LLM requests per minute (compare against provider
  rate limits), DB IOPS, Kafka throughput, pod counts.

### 1.5 Deliverable: `docs/01-requirements/plan-and-budget.md`
- A milestone timeline matching the build phases in Section 11.
- A monthly run-cost model: infrastructure plus LLM, at 1x, 3x, and 10x load.
- Cost controls: model routing, diagnosis caching, token budgets (see 2.6).

---

## 2. DESIGN

### 2.1 System architecture — deliverable: `docs/02-design/architecture.md` + ADRs
Record each major decision as an ADR in `docs/02-design/adr/NNN-title.md` (context,
options, decision, consequences). Required ADRs:
- ADR-001 **Modular monolith, two deployables.** A single codebase with strict module
  boundaries (Spring Modulith), deployed as:
  - `chat-api`: synchronous customer-facing chat and REST APIs (latency-sensitive).
  - `proactive-worker`: a Kafka consumer for bill-generated events (throughput-sensitive,
    bursty).
  Reason: the two workloads scale differently. Full microservices are rejected for v1
  (the team is small and the domain is still evolving). Serverless is rejected because of
  long-lived SSE streams and JVM cold starts.
- ADR-002 PostgreSQL as the system of record (ACID for money and actions) versus NoSQL.
- ADR-003 The LLM never does arithmetic; deterministic engines do (see 4.3).
- ADR-004 Human-in-the-loop via the ProposedAction pattern plus an autonomy ladder
  (see 8.2).
- ADR-005 LLM provider choice, fallback, and data residency (see 2.6).
- ADR-006 Kafka for events versus Spring ApplicationEvent (abstracted behind an
  interface; in-process for local dev, Kafka in staging and prod).

Diagrams (Mermaid, stored in the docs): C4 context, C4 container, a sequence diagram for
the chat flow, a sequence diagram for the proactive flow, and a deployment diagram.

### 2.2 Data architecture — deliverable: `docs/02-design/data-architecture.md`
- ER diagram and table list. Money columns are NUMERIC(14,2); never use float.
- Data flow diagram: BSS → gateways → engines → LLM → actions → BSS, marking where
  PII crosses each boundary.
- Usage layout (decision Q-6, option C; ADR-007): bill-period aggregates
  (`usage_period`, `usage_period_roaming`) for 6 months + current; daily rows
  (`usage_daily`, `usage_daily_roaming`) only for the current and previous cycle; per-session
  detail from BSS on demand. Every usage row carries `billed_period` and `usage_period`, so
  late usage (for example roaming via TAP files) is billed as a prior-period charge and
  recomputed idempotently per ingest batch.
- Partitioning: the usage tables above (by `billed_period`), `bill` and `bill_line_item`
  (by `bill_period`), `audit_events`, and `chat_messages` are range-partitioned by month
  (native PostgreSQL partitioning), with a retention job that drops or archives old
  partitions.
- Indexes: design them for every query path (account_id + bill_period, and so on).
  Include EXPLAIN plans for the top 5 queries in the doc.
- Replication: a primary plus a read replica. Read-only tools and history queries use
  the replica through a routing DataSource.
- Sharding: NOT implemented in v1. Document the path to it (hash on account_id, using
  Citus or app-level sharding) and the trigger metric for when it becomes necessary.
- Object storage (S3; MinIO locally): archived audit partitions, eval reports, and
  exported conversation transcripts for QA.
- CDN: only for the static chat UI assets (documented; not needed locally).
- Backup and DR: automated daily snapshots plus PITR (WAL archiving). **RPO ≤ 15 min,
  RTO ≤ 4 h.** Cross-region snapshot copy. Include a restore procedure.
- Data retention and privacy: define a retention period per table; PII masking (4.6).

### 2.3 Scalability design — deliverable: `docs/02-design/scalability.md`
- Horizontal scaling: both deployables are stateless (chat memory lives in the DB).
  - `chat-api`: HPA on CPU plus active SSE connections.
  - `proactive-worker`: KEDA autoscaling on Kafka consumer lag.
- Load balancing: an L7 ingress with SSE-friendly timeouts (no sticky sessions needed).
- Caching (multi-level):
  - L1 Caffeine (in-process): plan catalog and policy config, TTL 10 min.
  - L2 Redis: bill summaries (TTL 15 min), and **diagnosis per bill_id** (computed once,
    reused by both the proactive and chat flows; this is the biggest cost saver).
  - Cache invalidation on a bill-adjustment event.
- Async via Kafka: `bill.generated`, `bill.anomaly.detected`, `action.executed`, and
  `notification.created` topics. Define the partition key (account_id), retries, and a
  DLQ for each.
- Backpressure: the proactive worker limits concurrent LLM calls (a semaphore sized to
  the provider rate limit) so a batch run cannot starve live chat. Live chat and batch use
  separate API keys or quotas.
- Rate limiting: Bucket4j backed by Redis. Per-customer limit (e.g., 20 chat turns/hour)
  and a global limit.

### 2.4 Non-functional requirements — deliverable: `docs/02-design/nfr.md`
| NFR | Target |
|---|---|
| Chat first meaningful content (deterministic spike summary: amount + top driver, see 4.6) | p95 < 1.5 s |
| Chat first LLM-generated word | p95 < 8 s |
| Chat full response (with tools) | p95 < 15 s |
| Non-LLM REST APIs | p95 < 300 ms, p99 < 800 ms |
| Proactive batch | all flagged bills of a cycle diagnosed within 6 h |
| Availability (chat-api) | 99.9% monthly |
| RPO / RTO | 15 min / 4 h |
| Diagnosis accuracy (eval set) | ≥ 95% correct primary cause |
| False positive on normal bills | 0 invented issues in the eval set |
| Cost | Configurable max cost per conversation; alert above it |

### 2.5 Security architecture — deliverable: `docs/02-design/security.md`
- AuthN/AuthZ: an OAuth2/OIDC resource server (Keycloak locally). Roles: CUSTOMER,
  CARE_AGENT, SUPERVISOR, ADMIN. Customers can access only their own account. The
  customer identity comes from the token, never from LLM tool arguments.
- TLS everywhere, encryption at rest (DB, Redis, S3), and secrets in Vault or a cloud
  secrets manager (never in the repo).
- STRIDE threat model plus the **OWASP Top 10 for LLM Applications** (prompt injection,
  sensitive info disclosure, excessive agency, and so on), each with a mitigation.
- Minimize PII sent to the LLM: mask the MSISDN (show only the last 4 digits), send no
  full name or address, and give the LLM usage and charges only.
- Compliance: a section on India's DPDP Act 2023 obligations (consent, purpose limitation,
  retention) and telecom regulatory considerations. Mark these for legal review. Do not
  claim compliance.
- Data residency: document where the LLM processes data. Option: access Claude through a
  cloud provider region that keeps data in-country, if one is available (verify current
  regional availability; do not assume).

### 2.6 LLM architecture — deliverable: `docs/02-design/llm-architecture.md`
- Chat and tool calling: Anthropic Claude via Spring AI.
  - Live chat: `claude-sonnet-5` (reasoning quality matters here).
  - Proactive batch diagnosis: the current Haiku-tier model (config) (cheaper, high volume). At the time of writing
    this is `claude-haiku-4-5-20251001`; the model is chosen in Phase 5a and switched only
    through the eval-gated process in `llm-architecture.md`.
  - Implement these as two ChatClient beans. Model names come from config.
- Embeddings for RAG: Spring AI Transformers (ONNX, all-MiniLM-L6-v2, 384 dims) running
  in-process. Anthropic is used only for chat.
- Local dev: an Ollama profile with a tool-calling-capable model.
- Resilience: Resilience4j timeout, retry with backoff on 429/5xx, and a circuit breaker.
  **Fallback:** if the LLM is unavailable, return the deterministic diagnosis rendered
  through a template ("Your bill is higher mainly because of roaming charges of ₹X…").
  This is possible because all the numbers come from Java engines.
- Token budget: a max tool calls, max input tokens, and max output tokens per turn.
  Truncate or summarize chat memory beyond N messages.
- Prompt management: prompts are versioned files (`prompts/system.v1.st`). The prompt
  version is logged with every response and can be rolled back by config.
- Temperature is a **per-model config property** (for example 0.2 where the model accepts
  it). It is omitted for models that reject sampling parameters (for example
  `claude-sonnet-5`). Verify with a live call in Phase 2.
- **Prompt caching is required.** The cost model and rate-limit headroom depend on it
  (see `docs/01-requirements/feasibility.md`). Before pinning the Spring AI version in
  Phase 3, verify that it supports Anthropic prompt caching. If it does not, stop and
  escalate to the owner.

### 2.7 Observability — deliverable: `docs/02-design/observability.md`
- Metrics: Micrometer → Prometheus → Grafana. Include Spring AI metrics (token usage,
  latency per model, and tool calls) plus custom metrics (diagnoses/min, actions proposed
  vs confirmed, auto-credits issued ₹/hour, cost per conversation, fallback rate).
- Logs: JSON structured logs with correlation id, conversation id, and prompt version,
  shipped to a central store (Loki locally; ELK or OpenSearch in prod). Mask PII in logs.
- Traces: OpenTelemetry → Tempo or Jaeger, covering chat → LLM → tool → gateway → DB.
- Dashboards (as JSON in the repo): Service Health, LLM Cost & Quality, Business
  Outcomes, Kafka Lag.
- Alerts (as Prometheus rules in the repo): error rate, p95 latency, LLM fallback rate
  above 5%, **auto-credit amount per hour above a threshold (a fraud/bug signal)**,
  consumer lag, and cost per hour.

---

## 3. TECHNOLOGY STACK
- Backend: Java 21, Spring Boot 3.x (latest stable), Spring Modulith, Maven.
- AI: Spring AI (latest stable 1.x, via the BOM) using ChatClient, @Tool, ChatMemory
  (JDBC), advisors, structured output, PGVector, and Transformers embeddings.
- Frontend: a minimal static HTML/JS chat page served by Spring (v1). Document an Angular
  upgrade path for a care-agent console.
- Data: PostgreSQL 16 + pgvector, Flyway, Spring Data JPA, Redis 7, Apache Kafka.
- Resilience and limits: Resilience4j, Bucket4j.
- Security: Spring Security OAuth2 Resource Server, Keycloak (local).
- Observability: Micrometer, OpenTelemetry, Prometheus, Grafana, Loki, Tempo.
- Cloud (reference target): AWS in an India region — EKS, RDS PostgreSQL, ElastiCache,
  MSK, S3, Secrets Manager. Keep the design cloud-portable.
- Containers and orchestration: Docker (built with Jib), Kubernetes, Helm, Argo Rollouts.
- IaC: Terraform.
- CI/CD: GitHub Actions.
- Testing: JUnit 5, AssertJ, Mockito, Testcontainers, WireMock, Pact (contract tests),
  Gatling (Java DSL), OWASP ZAP, Trivy, Semgrep or SpotBugs.

---

## 4. DEVELOPMENT

### 4.1 Package structure (Spring Modulith modules)
```
com.telco.billshock
├── agent/       ChatClient config, system prompt, advisors, orchestration, fallback
├── tools/       @Tool classes: BillingTools, UsageTools, CatalogTools, ActionTools
├── domain/      Customer, Account, Bill, BillLineItem, UsageRecord, Plan, AddOn,
│                Subscription, ProposedAction, AuditEvent, BillShockDiagnosis
├── bss/         Gateway interfaces + mock implementations modeled on TM Forum Open APIs:
│                TMF678 Customer Bill, TMF635 Usage, TMF620 Product Catalog,
│                TMF622 Product Ordering, TMF621 Trouble Ticket
├── analysis/    BillDiffEngine, AnomalyDetector, PlanSimulator (deterministic, no LLM)
├── proactive/   Kafka consumer, anomaly trigger, notification creation
├── policy/      RAG ingestion of policy documents
├── actions/     ProposedAction workflow, guardrail evaluation, executors
├── api/         REST controllers, SSE, DTOs, global exception handler
├── security/    Resource server config, PII masking
└── audit/       Immutable audit log
```
Add Modulith verification tests that fail the build on illegal cross-module
dependencies.

### 4.2 Design patterns (use them where they fit and name them in the code docs)
- Adapter/Gateway for BSS integrations (mock ↔ real swap).
- Strategy for anomaly detection rules and for resolution executors.
- Chain of Responsibility for guardrail checks.
- Template Method for the deterministic fallback explanations.
- Transactional Outbox for publishing Kafka events reliably with DB writes.
- Idempotent Consumer for Kafka handlers.

### 4.3 Critical agent rules
1. **The LLM never does money math.** All amounts, diffs, and simulations are computed
   in Java (BigDecimal, HALF_EVEN). The LLM selects tools and explains the results.
2. **Tools never trust LLM-supplied identity.** The account comes from the
   SecurityContext.
3. **Human-in-the-loop.** Action tools only create a ProposedAction
   (PENDING_CONFIRMATION). Execution happens only through the confirm endpoint or a
   policy auto-approval.
4. **Idempotency keys** on every action. Confirming twice must never double-credit.
5. **Audit** every tool call and action.
6. **Grounded answers only.** Cite only charges that appear in tool results. When data
   is missing, say so.

### 4.4 Tools (@Tool methods with precise descriptions)
Investigation (read-only):
- getBillSummary(billPeriod?)
- getBillHistory(months=6)
- diffBills(currentPeriod, baselineMonths=3): per-category delta ranked by contribution
- getLineItems(billPeriod, category?)
- getUsageDetails(billPeriod, type: DATA|VOICE|SMS|ROAMING)
- getActiveSubscriptions(): plan, add-ons, VAS with activation date, channel, and opt-in
  record

Recommendation (read-only, deterministic):
- searchPlanCatalog(filters)
- simulatePlans(billPeriod): re-rate actual usage; return the top 3 by savings
- getPolicy(question): RAG over the policy documents

Resolution (each creates a ProposedAction only):
- proposeGoodwillCredit(amount, reason, lineItemIds)
- proposeVasUnsubscribe(subscriptionId, requestRefund)
- proposeThirdPartyBarring()
- proposePlanChange(planId, effective: IMMEDIATE|NEXT_CYCLE)
- proposeAddOn(addOnId)
- raiseDispute(lineItemIds, reason)
- escalateToHuman(summary, reason)

### 4.5 Guardrails (configurable)
- Goodwill credit auto-approval: ≤ 15% of the bill AND ≤ ₹500 AND no credit in the
  last 6 months. Above that: customer confirmation plus a supervisor flag. Above ₹2,000:
  escalate.
- VAS with no double opt-in record → propose a full refund.
- A plan change always needs explicit confirmation.
- Max 8 tool calls per turn, then escalate.
- Off-topic requests → polite redirect. Never reveal the system prompt or thresholds.

### 4.6 System prompt (`resources/prompts/system.v1.st`)
**diffBills pre-fetch (orchestration, not a prompt rule):** before the first LLM call of a
conversation about a bill, the orchestrator runs `diffBills` for the current period in
Java. It then (1) streams a deterministic one-line summary of the spike (total excess
amount and top driver, rendered through the fallback templates, so no LLM is involved)
within 1 s, and (2) injects the `diffBills` result into the LLM context. The LLM then
continues the answer. The agent does not re-call `diffBills` for the same period unless
the user asks about a different period or baseline.

The agent must: investigate before explaining (using the pre-fetched diffBills result); explain the cause
in 2–4 plain sentences with ₹ amounts, largest driver first; quantify every
recommendation; offer at most 3 options; state clearly what needs confirmation versus
what was done automatically; explain jargon; and return a structured BillShockDiagnosis
(causes[], totalExcess, confidence, recommendedActions[]).

### 4.7 API design (REST, OpenAPI 3 spec generated with springdoc)
- POST /api/v1/chat (SSE) { conversationId, message }
- GET  /api/v1/bills/{period}/diagnosis
- GET  /api/v1/actions?status=
- POST /api/v1/actions/{id}/confirm | /reject (requires an Idempotency-Key header)
- GET  /api/v1/notifications
- POST /api/v1/feedback { conversationId, rating, comment }
- GET  /api/v1/audit?conversationId= (SUPERVISOR role)
- POST /api/v1/admin/simulate-bill-run (ADMIN role, dev and staging only)
- Versioned URLs; RFC 7807 Problem Details for errors; pagination on list endpoints.

### 4.8 Error handling and logging
- A global @RestControllerAdvice mapping to Problem Details with error codes.
- Typed exceptions: BssUnavailableException, GuardrailViolationException,
  LlmUnavailableException (triggers the fallback), and so on.
- Structured logs with MDC (correlationId, conversationId, promptVersion). No PII.

### 4.9 Code standards
- Spotless (formatting), Checkstyle, SpotBugs, and Semgrep in the build.
- JaCoCo coverage gate: 80% on analysis/ and actions/, 70% overall.
- CONTRIBUTING.md: branch strategy (trunk-based with short-lived branches), PR template
  with a checklist, conventional commits, and review rules (2 approvals for actions/ and
  security/).

### 4.10 Seed data (Flyway + JSON fixtures; INR with 18% GST)
Six demo customers with 6 months of history:
1. International roaming in the UAE with no pack (about 3.5x)
2. Domestic data overage
3. Third-party VAS with no opt-in record
4. Mid-cycle plan upgrade proration
5. A duplicate line item (billing error → dispute)
6. A normal bill (the agent must NOT invent issues)
Also: 8 plans, 5 add-ons, and 4 policy markdown documents. Plus a synthetic data
generator that can create N customers (used for performance tests).

---

## 5. TESTING (deliverable: `docs/05-testing/test-strategy.md`)
- **Unit:** engines, guardrails, and PII masking, with exact BigDecimal assertions.
- **Integration:** Testcontainers (Postgres, Redis, Kafka), WireMock BSS, and a stubbed
  ChatModel for deterministic tool-wiring tests.
- **Contract:** Pact consumer tests for every BSS gateway, so real BSS teams can verify
  them later.
- **System/E2E:** the full flow through the API for all 6 scenarios, using the stubbed
  LLM.
- **LLM evals** (@Tag("llm"), real model): for each scenario, check the primary cause,
  the expected tool calls, that nothing is executed without confirmation, and that
  scenario 6 produces no false issues. Use the Spring AI evaluators. Write the results
  report to `build/eval-report.json`.
- **UAT:** `docs/05-testing/uat-scripts.md` with step-by-step business scenarios and
  expected outcomes, for care-team sign-off.
- **Performance (Gatling):** load (the target from 1.4), stress (increase until
  breaking), and soak (8 h). Use a **stubbed LLM with realistic latency injection**, since
  load-testing the real provider is expensive and hits rate limits. Run a separate small
  test against the real LLM to measure actual latency.
- **Scalability:** a 10x load test verifying that HPA and KEDA scale out and the NFRs
  still hold.
- **Security:** a ZAP baseline scan, Trivy image scans, dependency scanning, and a
  prompt-injection suite (another account's data, fake admin requests, "ignore
  instructions", malicious text inside a VAS name field).
- **Resilience/chaos:** kill pods, cut off Redis, and simulate LLM 429/timeouts. Verify
  the fallback and circuit breaker.

---

## 6. DEVOPS & INFRASTRUCTURE
- **Containers:** Jib builds; distroless or minimal base images; run as non-root.
- **Local:** docker-compose.yml with the app, Postgres/pgvector, Redis, Kafka, Keycloak,
  MinIO, Prometheus, Grafana, Loki, and Tempo. Plus an Ollama profile.
- **Kubernetes:** a Helm chart in `deploy/helm/` with Deployments for both deployables,
  HPA, KEDA ScaledObject, PDBs, resource requests and limits, liveness/readiness/startup
  probes, NetworkPolicies, and an Argo Rollouts canary.
- **IaC:** Terraform in `infra/terraform/` with modules for VPC, EKS, RDS (Multi-AZ, PITR,
  read replica), ElastiCache, MSK, S3, and Secrets Manager. Separate state per
  environment. Do NOT apply; provide `plan` instructions only.
- **Environments:** dev, qa, staging, prod. Config via Spring profiles plus Helm values
  per environment. Staging mirrors prod topology.
- **CI/CD (GitHub Actions)** in `.github/workflows/`:
  1. PR: build, unit and integration tests, Spotless/Checkstyle/SpotBugs/Semgrep,
     coverage gate, Modulith verification.
  2. Main: all of the above, then build the image, Trivy scan, push, and deploy to qa.
  3. Staging: deploy, run the E2E suite, **run the LLM eval gate (block if accuracy drops
     below target)**, and a ZAP scan.
  4. Prod: a manual approval, then an Argo Rollouts canary.
- **DB migrations:** Flyway with the **expand–contract** pattern for zero downtime (add
  the column → dual-write → backfill → switch reads → drop in a later release). Document
  it in `docs/06-devops/migrations.md`. Migrations run as a pre-deploy Kubernetes Job,
  not at app startup.

---

## 7. PRE-PRODUCTION STAGING (deliverable: `docs/07-staging/readiness-checklist.md`)
- A prod-like environment using anonymized or synthetic data (never real PII).
- A performance benchmark run with results recorded as a baseline.
- A load simulation during a synthetic bill-cycle run (the proactive batch plus chat
  peak together).
- A security audit: ZAP, Trivy, dependency report, and a threat-model review sign-off.
- Backup/restore drill: restore to a point in time and verify the data. Record the
  actual RTO.
- DR drill: restore in the secondary region from a cross-region snapshot. Record the RTO
  and RPO.
- An LLM outage drill: block the provider and verify the fallback responses.

---

## 8. DEPLOYMENT STRATEGY (deliverable: `docs/08-deployment/strategy.md`)

### 8.1 Canary (chosen over blue-green; document why)
LLM behavior can change with prompt or model updates in ways unit tests miss, so traffic
shifts gradually (5% → 25% → 50% → 100%) with automated analysis on error rate, p95
latency, fallback rate, and negative-feedback rate. Blue-green is kept as an option for
large infrastructure changes.

### 8.2 Autonomy ladder (feature flags, a kill switch at each level)
- Level 0 — **Explain only:** diagnosis and recommendations; no action tools.
- Level 1 — **Propose:** actions need customer confirmation.
- Level 2 — **Auto-approve:** small credits within policy.
Launch at Level 0, and move up only after the metrics from the previous level are
reviewed. Every level can be switched off instantly without a redeploy.

### 8.3 Rollback plan
- The application: Argo Rollouts abort → previous ReplicaSet.
- The prompt or model: a config rollback to the previous prompt version or model name.
- Autonomy: a feature flag down to Level 0.
- The DB: expand–contract ensures that the previous app version works with the new
  schema; no down-migrations in prod.

### 8.4 Other steps
DNS/ingress cutover (for a first launch), a cache warm-up Job (plan catalog, policy
embeddings loaded, catalog preloaded to Redis), and a post-deploy smoke test.

---

## 9. PRODUCTION LAUNCH (deliverable: `docs/09-launch/`)
- `launch-checklist.md`: dashboards live, alerts routed, on-call rota, runbooks linked,
  baselines recorded, and rollback tested.
- `runbooks/` (one per incident): LLM provider outage, cost spike, **wrong or excessive
  credits issued**, Kafka lag growing, DB failover, prompt regression, and suspected
  prompt-injection attack.
- `on-call.md`: severity levels, escalation path, and response time targets.
- `communication-plan.md`: templates for internal launch notes, care-team briefing, and
  customer-facing incident messages.

---

## 10. POST-PRODUCTION (deliverable: `docs/10-operations/`)
- Performance monitoring against the NFR baselines, reviewed weekly.
- Scaling triggers documented (CPU, SSE connections, Kafka lag, DB connections), plus
  a capacity review each quarter.
- Backups: automated, with a restore test every month.
- Security patching: Renovate or Dependabot, a monthly patch window, and a critical-CVE
  SLA.
- Log analysis: weekly review of fallbacks, escalations, and refused requests.
- User feedback: thumbs up/down plus a comment in the UI, stored and reviewed. **Every
  production failure becomes a new eval case**, so the eval set grows over time.
- Model and prompt change process: any new model or prompt version must pass the full
  eval suite in staging and then go through a canary.
- Monthly cost review: tokens, cost per resolved case, and cache hit rate.

---

## 11. BUILD ORDER (phase gates — stop and report after each)

**Phase gates apply to every phase and every sub-phase** (3a, 4a, 5a, 6a, 3b, 4b, 5b,
6b). After each one, stop, summarize the changes, list assumptions and open questions,
and wait for approval before starting the next.

**Execution order:**
1 → 2 → **MVP demo slice** (3a → 4a → 5a → 6a) → **remainder** (3b → 4b → 5b → 6b) →
7 → 8 → 9 → 10 → 11 → 12.

The MVP demo slice is the smallest subset of Phases 3–6 that demos all 6 seed scenarios
end to end (chat, diagnosis, recommendation, confirm flow, README). Its detailed scope is
in `docs/01-requirements/plan-and-budget.md` §2a. Each "a" sub-phase delivers its slice
of the phase below, and the matching "b" sub-phase delivers the rest. The demo wrap-up
(static chat page, README with the 6-scenario demo script, one stubbed-LLM E2E test per
scenario) is part of 6a. `./mvnw verify` must pass at the end of every code sub-phase.

1. **Docs I:** PRD, feasibility, capacity estimates, plan and budget, NFRs.
2. **Docs II:** architecture, ADRs, data architecture, scalability, security, LLM
   architecture, observability.
3. **Foundation:** project skeleton, Modulith modules, Flyway schema, seed data, mock
   BSS gateways, docker-compose, and the CI PR workflow.
   - 3a (slice): skeleton, slice modules + Modulith verification, slice schema
     (partitioned DDL), seed data for the 6 customers, 8 plans and 5 add-ons, in-process
     mock gateways, compose with PostgreSQL only. Pin Spring AI only after verifying
     Anthropic prompt caching (2.6).
   - 3b (remainder): policy docs, synthetic data generator, WireMock, the remaining
     compose services, CI PR workflow, code-quality plugins, retention job, read-replica
     routing.
4. **Deterministic core:** diff, anomaly, and simulator engines, and guardrails, with
   unit tests.
   - 4a (slice): BillDiffEngine, PlanSimulator, the guardrails the scenarios exercise,
     with unit tests.
   - 4b (remainder): AnomalyDetector, remaining guardrails, JaCoCo gate.
5. **Agent:** ChatClient beans, tools, memory, system prompt, SSE chat, fallback, and
   PII masking.
   - 5a (slice): Sonnet ChatClient, read-only tools, diffBills pre-fetch + spike summary
     (4.6), fallback templates, system prompt, JDBC memory, SSE chat, prompt caching,
     MSISDN masking, in-memory demo users in the SecurityContext.
   - 5b (remainder): Haiku ChatClient, full resilience, token-budget summarization,
     Ollama profile, log masking; Haiku-first routing decision (measured turn mix).
6. **Actions:** the ProposedAction workflow, confirm endpoints, idempotency, audit, and
   the autonomy-ladder flags.
   - 6a (slice): ProposedAction workflow, scenario action tools, list/confirm/reject with
     Idempotency-Key, mock executors, audit, autonomy fixed at Level 1, plus the demo
     wrap-up.
   - 6b (remainder): Level 2 auto-approval, supervisor queue and roles, audit API,
     runtime kill switch, outbox events.
7. **Async and scale:** Kafka with outbox, the proactive worker, Redis caching, and rate
   limiting.
8. **RAG and security:** policy ingestion, Keycloak integration, and the injection test
   suite.
9. **Observability:** metrics, traces, dashboards, and alert rules.
10. **Testing depth:** contract tests, E2E, LLM evals, Gatling, and chaos tests.
11. **Deploy:** Helm, KEDA, Argo Rollouts, Terraform, and the full CI/CD pipeline.
12. **Ops docs:** staging checklist, deployment strategy, runbooks, and operations guides.

Before Phase 1, reply with: (1) your understanding of the scope, (2) the full list of
assumptions you'll use, and (3) any questions. Then begin.

---

## 12. FINAL CHECKLIST (verify each item before declaring done)
| Aspect | Check |
|---|---|
| Database | Normalized schema, indexes with EXPLAIN evidence, monthly partitions, read replica routing |
| Caching | L1 Caffeine + L2 Redis, invalidation on adjustments, diagnosis-per-bill cache |
| Load balancing | Stateless pods, SSE-friendly ingress, HPA + KEDA |
| Rate limiting | Per-customer and global (Bucket4j/Redis), LLM concurrency limit |
| Logging | Centralized JSON logs, correlation ids, PII masked |
| Monitoring | Metrics, traces, 4 dashboards, alert rules in the repo |
| Security | OIDC, RBAC, TLS, encryption at rest, secrets manager, OWASP LLM Top 10 mitigations |
| Backup/DR | PITR, cross-region copy, restore drill done, RTO < 4 h measured |
| LLM quality | Eval suite passing, eval gate in CI, prompt versioning, fallback tested |
| Cost | Cost per conversation tracked, model routing, token budgets, cost alerts |
| Agent safety | HITL, guardrails, idempotency, audit log, autonomy ladder with kill switch |
| Documentation | PRD, ADRs, architecture, API spec, runbooks, UAT scripts, README |
