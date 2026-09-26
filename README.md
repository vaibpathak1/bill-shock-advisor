# Bill Shock Advisor

## What and why

"Bill shock" is a telecom bill that is much higher than the customer expects. Typical causes
are roaming without a pack, data overage, a third-party subscription the customer never
knowingly started, or a duplicate charge. It drives care calls, disputes and churn. Bill Shock
Advisor is an AI agent for postpaid customers (India: ₹, GST) that finds out why the bill went
up, explains it with exact amounts, and proposes a fix: a plan change, an unsubscribe with
refund, a dispute or a goodwill credit. Nothing changes on the account until the customer
confirms, and every amount comes from deterministic Java code, not from the model.

> **Status (honest):** the MVP slice (Phases 3a–6a) is built and `./mvnw verify` passes
> (247 unit and 146 integration tests). The LLM path is tested against a local fake of the
> Anthropic API; **checks against the real model have not been run yet** (no API credits).
> The [demo](#scripted-demo-all-six-scenarios) therefore uses a **scripted model**: only the
> model's words and tool choices are fixed; tools, gates, guardrails, actions and audit run for
> real. There is also a [template-only mode](#demo-without-an-api-key) with no model at all.

## Architecture

[![Runtime architecture](docs/diagrams/01-architecture.png)](docs/diagrams/01-architecture.html)

The PNG is a snapshot. For the interactive version, with guided views and a link from each box
to its source file, download and open [01-architecture.html](docs/diagrams/01-architecture.html).
GitHub shows HTML files as source rather than rendering them. Four more diagrams are in
[docs/diagrams/](docs/diagrams/): one chat turn, the action lifecycle, customer data flow, and
the confirm flow.

## Key engineering decisions

- **The LLM never does money math.** Java engines (`BillDiffEngine`, `PlanSimulator`) compute
  every amount in `BigDecimal`; the model only chooses tools and explains.
  [ADR-003](docs/02-design/adr/003-llm-never-does-arithmetic.md)
- **Identity comes from the SecurityContext, never from the model.** No tool has an account
  or phone-number parameter (a reflection test enforces this), so a prompt injection cannot
  read another customer's data. Another customer's conversation or action returns 404.
  [ADR-009](docs/02-design/adr/009-identity-from-security-context.md)
- **Human in the loop, with an idempotent confirm.** Action tools only create a
  `ProposedAction`. The customer's confirm carries an `Idempotency-Key`; guardrails run again
  on fresh data, and the BSS call happens outside the database transaction.
  [ADR-004](docs/02-design/adr/004-hitl-proposed-action-autonomy-ladder.md),
  [actions.md §6](docs/03-development/actions.md)
- **An unknown outcome is not a failure.** If the billing system times out, the effect may
  already exist, so the action stays `EXECUTING` and is re-driven with the same key. Only a
  definite rejection marks it `FAILED`. [ADR-004](docs/02-design/adr/004-hitl-proposed-action-autonomy-ladder.md)
  (amended 2026-09-26), [actions.md §6.3](docs/03-development/actions.md)
- **Sentence-level grounding gate.** Each sentence is checked before it is streamed: every ₹
  amount must match a tool result exactly, including its GST label. A mismatch triggers one
  regeneration; anything else falls back to a deterministic template answer.
  [ADR-003](docs/02-design/adr/003-llm-never-does-arithmetic.md),
  [agent.md §8.1](docs/03-development/agent.md)

## How it was built

- **Spec first.** [SPEC.md](SPEC.md) came before any code, followed by requirements, design
  docs and ADRs ([docs/](docs/)). Anything unknown is written down as a numbered assumption.
- **Phase gates.** The work is split into phases (SPEC §11). Each phase ends at a gate: a
  summary, the open questions, and a stop until the owner approves. Decisions and answers are
  logged in [docs/PROGRESS.md](docs/PROGRESS.md).
- **AI-assisted development with Claude Code.** The coding agent works under the rules in
  [AGENTS.md](AGENTS.md): one phase at a time, check library APIs against the pinned jars
  instead of writing them from memory, never skip a failing test, never touch secrets.
- **Human review at every gate.** The repository owner reviews each design note and each
  phase's code before the next phase starts; the review answers are in PROGRESS.md. Phases 1–6a
  have passed their gates; the MVP slice is tagged `v0.1.0-mvp`. The live-model checks from 5a
  are still open (see Status above).

## Prerequisites

- Java 21
- Docker (Docker Desktop or equivalent), **running**. `docker compose` hosts the local
  database, and the integration tests start their own PostgreSQL with Testcontainers.
- Optional: a `psql` client on the host. You can also use the one inside the container.

The Maven Wrapper (`./mvnw`) downloads Maven itself; no local Maven install is needed.

## Run locally

All commands run from the repository root.

**1. Create `.env` from the template** and set at least `POSTGRES_PASSWORD`,
`DEMO_USER_PASSWORD` and either `ANTHROPIC_API_KEY_CHAT` or `BILLSHOCK_LLM_MODE=TEMPLATE_ONLY`
(deterministic answers only, no key and no cost):

```bash
cp .env.example .env
```

`.env` is git-ignored. Never commit it. If something else on your machine already uses
port 5432 (for example a local PostgreSQL), set `POSTGRES_PORT` in `.env` to a free port
such as `55432`.

**2. Start PostgreSQL** (16, with pgvector) and wait until it is healthy:

```bash
docker compose up -d --wait
```

`docker compose` reads `.env` on its own. **The application does not.**

**3. Load `.env` into your shell**, so the app sees the same database settings. Do this in
every new terminal:

```bash
set -a; source .env; set +a
```

`set -a` exports every variable that `source` defines, and `set +a` turns that off again.
If you skip this step, the app fails at startup with a password or connection error,
because `application-dev.yml` reads `POSTGRES_PASSWORD`, `POSTGRES_PORT` and the other
variables from the environment.

**4. Run the application with the `dev` profile:**

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The `dev` profile applies the Flyway migrations **and the demo seed data** on startup
(the seed never runs outside `dev` and `test`). It also switches on the in-process mock
BSS gateways (`mock-bss`). You should see `Successfully applied 9 migrations` and then
`Started BillShockAdvisorApplication`. The app listens on port 8080. Startup stops with a
clear message if `DEMO_USER_PASSWORD` is empty, or if `BILLSHOCK_LLM_MODE=LLM` and
`ANTHROPIC_API_KEY_CHAT` is empty.

**Try the chat** (server-sent events; users `cust1001` … `cust1006`, one per demo scenario).
With `LLM` mode this calls Anthropic and costs money:

```bash
curl -N -u "cust1001:$DEMO_USER_PASSWORD" -H 'Content-Type: application/json' -d '{"message":"Why is my bill so high?"}' http://localhost:8080/api/v1/chat
```

The first event is the deterministic `summary`; then `progress` and `token` events (one checked
sentence each), an `action` event for each proposal, `diagnosis` and `done`. To continue the
conversation, send the `conversationId` from `done` with the next message.

The chat page is at http://localhost:8080 (sign in with a demo user and `DEMO_USER_PASSWORD`).

**Proposed actions** (docs/03-development/actions.md): the agent only *proposes*; the customer
confirms or rejects. Confirm and reject need an `Idempotency-Key` header; repeating a request
with the same key returns the stored response and never executes twice:

```bash
curl -s -u "cust1003:$DEMO_USER_PASSWORD" http://localhost:8080/api/v1/actions?status=PENDING_CONFIRMATION
```

```bash
curl -s -u "cust1003:$DEMO_USER_PASSWORD" -X POST -H "Idempotency-Key: my-key-1" http://localhost:8080/api/v1/actions/1/confirm
```

**5. Connect with psql**, using the client inside the container (nothing to install):

```bash
docker compose exec postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"
```

Or with a `psql` on the host, after step 3:

```bash
psql "postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:$POSTGRES_PORT/$POSTGRES_DB"
```

Try `SELECT account_id, bill_period, total FROM bill WHERE bill_period = DATE '2026-09-01';`.
The 6 demo accounts and their expected numbers are described in
[docs/03-development/seed-scenarios.md](docs/03-development/seed-scenarios.md).

**Stop:**

```bash
docker compose down
```

To also delete the database volume, add `-v`. You need this after a migration file
(`V1`–`V6`) has been edited in place: until the first real deployment, migrations are
edited rather than added (PROGRESS.md, Phase 3a answer 3), and Flyway refuses to start
when a checksum no longer matches.

### Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `password authentication failed` or `no password was provided` at app startup | `.env` not loaded into the shell | Step 3: `set -a; source .env; set +a` |
| `docker compose up` fails with `set POSTGRES_PASSWORD in .env` | `.env` missing or the password empty | Step 1 |
| `port is already allocated` / `address already in use` | Another service on 5432 | Set `POSTGRES_PORT` in `.env`, re-run steps 2 and 3 |
| `Validate failed: Migrations have failed validation` / checksum mismatch | A migration was edited after it ran | `docker compose down -v`, then start again |
| Integration tests fail with `Could not find a valid Docker environment` | Docker is not running | Start Docker; `./mvnw test` (unit tests only) does not need it |

## Scripted demo: all six scenarios

> **Scripted model — not a live LLM.** This demo replaces only the model's words and tool
> choices with fixed scripts (the same ones the scenario E2E tests use). Everything else runs
> for real: the tools, the grounding gates, the guardrails, the proposal workflow, confirm with
> idempotency, the mock BSS and the audit log. The page shows the same banner. No request can
> reach Anthropic, and no key is needed or sent. The scripts live in test sources
> (`src/test/java`) and are never part of the application jar.

`.env` needs `POSTGRES_PASSWORD` (plus `POSTGRES_PORT` if 5432 is taken) and a non-empty
`DEMO_USER_PASSWORD`.

**1. Reset and start the database.** `-v` deletes the local data. That is needed after a
migration was edited in place (V5 changed in Phase 6a); the seed is re-applied on startup.

```bash
docker compose down -v
```

```bash
docker compose up -d --wait
```

**2. Start the scripted demo** (terminal A). It runs the application with the `dev` profile
from the test classpath (`spring-boot:test-run`):

```bash
set -a; source .env; set +a
```

```bash
./mvnw spring-boot:test-run -Dspring-boot.run.main-class=com.telco.billshock.demo.ScriptedDemoApplication
```

Wait for `Started BillShockAdvisorApplication`.

**3. Open http://localhost:8080**, pick a demo customer, enter `DEMO_USER_PASSWORD` and send
the suggested question ("Why is my bill so high?"). Each proposal appears as a card with
**Confirm** and **Reject**. "My requests" lists every proposal and its status. "New chat"
starts over.

| Customer | Scenario | What the agent says and proposes | Click | Expected result |
|---|---|---|---|---|
| `cust1001` | UAE roaming, no pack (≈3.5x) | Roaming charges of ₹2,094.50 incl. GST; the IR_GCC_7D pack would have saved ₹1,033.68 incl. GST; a goodwill credit as if the pack had been active | Confirm | **Awaiting supervisor**: the credit is above the self-service policy, so a supervisor reviews it (supervisor queue: Phase 6b). Nothing is credited yet |
| `cust1002` | Domestic data overage | On PP_499 the bill would have been ₹588.82 incl. GST, a saving of ₹317.00 incl. GST; a plan change from the next bill cycle | Confirm | **Executed**: a TMF622 plan-change order |
| `cust1003` | Third-party VAS without double opt-in | Astro Daily started without a confirmed opt-in; unsubscribe with a refund of ₹231.28 incl. GST, and third-party barring; Cricket Scores stays (complete opt-in) | Confirm the Astro Daily card, then ask "Is it done?" | **Executed**: unsubscribe and refund, each once. The follow-up answer says it has been cancelled and refunded, which the claim gate allows only because both steps are done. Ask the follow-up *before* confirming to see the gate rewrite the claim |
| `cust1004` | Mid-cycle plan upgrade | Two part-month rentals after the customer's own plan change on 1 September; expected | — | **No proposal** |
| `cust1005` | Duplicate line item | The PP_599 rental is charged twice; a dispute of ₹706.82 incl. GST (no goodwill credit) | Confirm | **Executed**: a TMF621 billing-dispute ticket with a reference |
| `cust1006` | Normal bill | In line with recent bills; nothing to change | — | **No proposal**, no invented issue |

Double-clicking Confirm, or clicking it again after a network error, is safe: the page reuses
the same `Idempotency-Key`, so the second request returns the stored response.

**4. What happened, in the database:**

```bash
docker compose exec postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT action_id, account_id, action_type, status, amount, gst_amount, external_ref FROM proposed_action ORDER BY action_id" -c "SELECT action_id, event_type, actor_type, actor_ref FROM audit_events WHERE action_id IS NOT NULL ORDER BY occurred_at"
```

The scripts answer only the suggested first question per customer (and "Is it done?" for
`cust1003`); anything else gets a fixed "this scripted demo has no further answers" reply.

| Symptom | Cause | Fix |
|---|---|---|
| Sign-in says wrong password | Terminal A did not load `.env`, or a different password was typed | Load `.env` in terminal A and restart step 2 |
| Startup: checksum mismatch | The database predates the V5 change | Step 1 (`down -v`) |
| An answer says "This scripted demo only covers the six seed scenarios" | The database is not the seeded one | Step 1 |

## Demo without an API key

The chat works end to end without Anthropic in **template-only mode**. That is the global kill
switch (ADR-005): every answer is the deterministic explanation rendered from the Java engines.
There is no LLM call and no cost.

**The mode is switched by `BILLSHOCK_LLM_MODE`** (property `billshock.llm.mode`): `LLM` (the
default) or `TEMPLATE_ONLY`. In `TEMPLATE_ONLY`, `ANTHROPIC_API_KEY_CHAT` is not read and may
be empty.

`.env` needs `POSTGRES_PASSWORD` (plus `POSTGRES_PORT` if 5432 is taken) and a non-empty
`DEMO_USER_PASSWORD`. The app refuses to start without the latter.

**1. Reset and start the database.** `-v` deletes the local data. That is needed after a
migration was edited in place (V4 in Phase 5a, V5 in Phase 6a); the seed is re-applied on startup.

```bash
docker compose down -v
```

```bash
docker compose up -d --wait
```

**2. Start the app in template-only mode** (terminal A). Setting the variable on the command
line overrides any `BILLSHOCK_LLM_MODE` in `.env`:

```bash
set -a; source .env; set +a
```

```bash
BILLSHOCK_LLM_MODE=TEMPLATE_ONLY ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Wait for `Started BillShockAdvisorApplication`.

**3. Ask as `cust1001`** (UAE roaming scenario), in terminal B. `-N` shows the events as they
arrive:

```bash
set -a; source .env; set +a
```

```bash
curl -N -u "cust1001:$DEMO_USER_PASSWORD" -H 'Content-Type: application/json' -d '{"message":"Why is my bill so high this month?"}' http://localhost:8080/api/v1/chat
```

Expected: four events, in this order (the conversation id and prompt hash differ on each run):

```
event:summary
data:{"conversationId":"…","text":"Your September 2026 bill is ₹2,094.50 incl. GST higher than the average of your previous 3 bills, mainly because of international roaming charges of ₹2,094.50 incl. GST."}

event:fallback
data:{"reason":"TEMPLATE_ONLY","text":"Your September 2026 bill is ₹2,094.50 incl. GST higher than the average of your previous 3 bills, mainly because of international roaming charges of ₹2,094.50 incl. GST. International roaming charges account for ₹2,094.50 incl. GST of the change. Adding GCC roaming pack 7 days (IR_GCC_7D) would have meant a new bill of ₹1,885.64 incl. GST, a saving of ₹1,033.68 incl. GST. I cannot give a fuller explanation right now; the figures above come straight from your bill."}

event:diagnosis
data:{"diagnosis":{"billPeriod":"2026-09","verdict":"MEANINGFUL_INCREASE","totalExcess":"₹2,094.50 incl. GST","causes":[{"group":"ROAMING","amountInclGst":"₹2,094.50 incl. GST","lineItemIds":[1001260902,1001260903,1001260904]}],"confidence":"HIGH","recommendedActions":[{"type":"ADD_ON","code":"IR_GCC_7D","summary":"Add IR_GCC_7D: new bill ₹1,885.64 incl. GST, saving ₹1,033.68 incl. GST."}],"source":"ENGINE"}}

event:done
data:{"conversationId":"…","promptVersion":"v1+…"}
```

**4. Ask as `cust1006`** (normal bill):

```bash
curl -N -u "cust1006:$DEMO_USER_PASSWORD" -H 'Content-Type: application/json' -d '{"message":"Why is my bill so high this month?"}' http://localhost:8080/api/v1/chat
```

Expected: the same four events, saying the bill is normal:

```
event:summary
data:{"conversationId":"…","text":"Your September 2026 bill of ₹617.14 incl. GST is in line with your recent bills."}

event:fallback
data:{"reason":"TEMPLATE_ONLY","text":"Your September 2026 bill of ₹617.14 incl. GST is in line with your recent bills. I cannot give a fuller explanation right now; the figures above come straight from your bill."}

event:diagnosis
data:{"diagnosis":{"billPeriod":"2026-09","verdict":"NORMAL","totalExcess":"₹7.08 incl. GST","causes":[],"confidence":"HIGH","recommendedActions":[],"source":"ENGINE"}}

event:done
data:{"conversationId":"…","promptVersion":"v1+…"}
```

**5. What to look for:**
- **`summary` comes first**, before anything else. It is the deterministic one-liner that
  NFR-01a requires within 1.5 s.
- **`fallback` with reason `TEMPLATE_ONLY` instead of `token` events.** No sentence comes from
  a model, and every amount carries its GST label. 1001 names the cause (roaming,
  ₹2,094.50 incl. GST) and a quantified option: the pack's new bill and saving are shown as
  two separate amounts. The ₹1,885.64 covers the plan, add-on and usage charges (A-99); for
  1001 that is the whole bill.
- **1006 has no invented problem:** verdict `NORMAL`, no causes, no recommendation. The
  ₹7.08 difference is below the "meaningful increase" rule (≥ ₹100 and ≥ 10%).
- **`diagnosis` has `source: ENGINE`**, because no model took part.
- The conversation is stored. To confirm that nothing called Anthropic, the first count should
  be `0`:

```bash
docker compose exec postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT count(*) AS llm_calls FROM llm_call_log" -c "SELECT account_id, verdict, source, total_excess FROM bill_diagnosis ORDER BY created_at"
```

The other demo users work the same way: `cust1002` data overage, `cust1003` third-party VAS,
`cust1004` mid-cycle plan change, `cust1005` duplicate charge (seed-scenarios.md §5).

| Symptom | Cause | Fix |
|---|---|---|
| `401` from curl | Terminal B has not loaded `.env`, or the app was started with a different password | Run `set -a; source .env; set +a` in terminal B |
| Startup: `needs DEMO_USER_PASSWORD` | `DEMO_USER_PASSWORD` is empty in `.env` | Set any value in `.env`, then repeat step 2 |
| Startup: `needs ANTHROPIC_API_KEY_CHAT` | The mode was not switched | Start with `BILLSHOCK_LLM_MODE=TEMPLATE_ONLY` as in step 2 |
| Startup: checksum mismatch | The database predates the V4 or V5 change | Step 1 (`down -v`) |

## Build and test

```bash
./mvnw verify
```

This runs everything: unit tests, Spring Modulith verification and the integration
tests (`*IT`, Testcontainers). Docker must be running. For unit tests only, without
Docker:

```bash
./mvnw test
```
