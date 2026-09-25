# AGENTS.md — Bill Shock Advisor

## Source of truth
- The full product and engineering specification is in `SPEC.md` at the repo root.
  Read the relevant sections of SPEC.md before starting any task.
- The build is organized into phases (SPEC.md Section 11). Work on ONE phase at a time.
- Progress is tracked in `docs/PROGRESS.md`. Read it at the start of every session and
  update it at the end (what was done, open questions, next step).

## Phase gate rules
- When a phase is complete: stop, summarize the changes, list assumptions and open
  questions, and wait for my approval. Do NOT start the next phase on your own.
- If the spec is ambiguous or conflicts with itself, ask instead of guessing.
- Anything unknown (volumes, budget, legal interpretation) must be written as an
  explicit ASSUMPTION in the docs.

## Tech stack (do not change without asking)
- Java 21, Spring Boot 4.x, Spring AI 2.x (via BOM), Spring Modulith 2.x, Maven Wrapper
  (`./mvnw`). Pinned versions and the reasons: ADR-008.
- PostgreSQL 16 + pgvector, Flyway, Redis, Kafka, Docker Compose
- Before using any Spring AI class or annotation, verify it exists in the version
  declared in pom.xml (check the dependency sources or the official docs). Do not guess
  APIs from memory.
- Boot 4 / Spring AI 2 / Jackson 3 are new — never write imports or APIs from memory;
  verify package names against the pinned jars.

## Build and test commands
- Build and all tests: `./mvnw verify`
- Unit tests only: `./mvnw test`
- Local infrastructure: `docker compose up -d`
- LLM eval tests (real model, costs money): `./mvnw verify -Pllm-evals` — run ONLY when
  I ask.
- A task is not done until `./mvnw verify` passes. Never skip, disable, or delete a
  failing test to make the build pass; fix the cause or tell me.

## Non-negotiable engineering rules
- All money math in Java with BigDecimal. The LLM never calculates amounts.
- Tool methods get the customer identity from the SecurityContext, never from LLM
  arguments.
- Action tools create a ProposedAction only; execution happens via confirmation.
- No secrets in the repo. Read keys from environment variables; keep `.env.example`
  updated with placeholder names only.
- Never create, modify, move or delete `.env`. For experiments, use a differently named temp
  file (e.g. `.env.test-tmp`) and remove only that file.
- Never run `terraform apply`, never push to remote, and never run destructive git
  commands (reset --hard, force push). I will do git pushes myself.

## Code style
- Package root: `com.telco.billshock`, organized as Spring Modulith modules per SPEC.md 4.1.
- Constructor injection only; records for DTOs; no Lombok.
- Every public tool method has a clear @Tool description.
- Keep changes small and focused; one logical change per commit message suggestion.
