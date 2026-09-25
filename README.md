# Bill Shock Advisor

An agentic assistant that investigates unexpected spikes in a telecom customer's bill,
explains the root cause, proposes tailored resolutions and executes approved actions
within guardrails. Specification: [SPEC.md](SPEC.md). Progress: [docs/PROGRESS.md](docs/PROGRESS.md).

> Status: Phase 5a (agent) is done: `POST /api/v1/chat` streams a grounded bill explanation
> (see "Demo without an API key"). Actions, the full demo script and the chat page arrive in
> Phase 6a.

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

The first event is the deterministic `summary`; then `token` events (one checked sentence
each), `diagnosis` and `done`. To continue the conversation, send the `conversationId` from
`done` with the next message.

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
migration was edited in place (V4 changed in Phase 5a); the seed is re-applied on startup.

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
| Startup: checksum mismatch | The database predates the V4 change | Step 1 (`down -v`) |

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
