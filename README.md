# Bill Shock Advisor

An agentic assistant that investigates unexpected spikes in a telecom customer's bill,
explains the root cause, proposes tailored resolutions and executes approved actions
within guardrails. Specification: [SPEC.md](SPEC.md). Progress: [docs/PROGRESS.md](docs/PROGRESS.md).

> Status: Phase 3a (foundation) is done: schema, seed data for the 6 demo scenarios and
> mock BSS gateways. The chat agent, the demo script and the UI arrive in Phases 5a–6a.

## Prerequisites

- Java 21
- Docker (Docker Desktop or equivalent), **running**. `docker compose` hosts the local
  database, and the integration tests start their own PostgreSQL with Testcontainers.
- Optional: a `psql` client on the host. You can also use the one inside the container.

The Maven Wrapper (`./mvnw`) downloads Maven itself; no local Maven install is needed.

## Run locally

All commands run from the repository root.

**1. Create `.env` from the template** and set at least `POSTGRES_PASSWORD`:

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
`Started BillShockAdvisorApplication`. The app listens on port 8080; Phase 3a has no
endpoints yet.

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
