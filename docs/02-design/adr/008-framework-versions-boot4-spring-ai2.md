# ADR-008: Framework versions: Spring Boot 4.1, Spring AI 2.0, Spring Modulith 2.1

| | |
|---|---|
| Status | Accepted (owner, 2026-09-25, Phase 3a Step 0) |
| Date | 2026-09-25 |
| Deciders | Repo owner |
| Related | SPEC §2.6, §3; AGENTS.md (tech stack); [llm-architecture.md](../llm-architecture.md) §2, §11; ADR-005, ADR-006; decisions Q-1, Q-2, Q-17; findings T-6, T-8 |

## Context

The Phase 2 docs assumed the latest Boot 3.x / Spring AI 1.x line: Spring Boot 3.5.16,
Spring AI 1.1.8 and Spring Modulith 1.4.13. Before pinning anything in `pom.xml`, Phase 3a
checked whether Boot 4.x and Spring AI 2.x are GA, how long each line is supported, and
whether each supports Anthropic prompt caching (Q-2), which the cost model depends on.

Sources are official only. All were retrieved on 2026-09-25:
- Maven Central: `maven-metadata.xml`, POMs and sources jars under
  `repo1.maven.org/maven2/org/springframework/{boot,ai,modulith}/`, and
  `com/anthropic/anthropic-java-core/2.52.0/`. The SHA-1 of both `spring-ai-anthropic`
  sources jars matched the published `.sha1`.
- spring.io support data: `api.spring.io/projects/{spring-boot,spring-ai,spring-modulith,spring-framework}/generations`.
  This is the data behind the "Support" tab of the spring.io project pages.

### GA releases (Maven Central)

| | Stack A (Boot 3.x line) | Stack B (Boot 4.x line) |
|---|---|---|
| Spring Boot | 3.5.16 (2026-06-25, the last 3.5.x) | **4.1.1** (2026-08-20). 4.2.0 is at M2 (milestone, not GA) |
| Spring AI | 1.1.8 (2026-06-12, the last 1.1.x) | **2.0.1** (2026-08-20). 2.1.0 is at M1 (milestone, not GA) |
| Spring Modulith | 1.4.13 (2026-08-25), built on Boot 3.5.16 | **2.1.1** (2026-08-25), built on Boot 4.1.1 |
| Spring Framework | 6.2.19 | 7.0.9 |

`spring-ai-autoconfigure-model-anthropic:2.0.1` compiles against Boot 4.1.1, and
`spring-modulith-core:2.1.1` against Boot 4.1.1 / Framework 7.0.9, so the three Stack B
versions are built for each other.

### Support windows (spring.io)

| Line | First release | OSS support ends | Commercial support ends |
|---|---|---|---|
| Boot 3.5.x | 2025-05 | **2026-06-30 (already ended)** | 2032-06-30 |
| Spring AI 1.1.x | 2025-11 | **2026-06-30 (already ended)** | 2032-06-30 |
| Framework 6.2.x | 2024-11 | 2026-06-30 (already ended) | 2032-06-30 |
| **Boot 4.1.x** | 2026-06 | **2027-07-31** | 2028-07-31 |
| **Spring AI 2.0.x** | 2026-06 | **2027-07-31** | 2028-07-31 |
| Framework 7.0.x | 2025-11 | 2027-07-31 | 2028-07-31 |
| Boot 4.2.x (planned) | 2026-11-30 | 2027-12-31 | 2028-12-31 |

spring.io lists no support dates for Spring Modulith. Each Modulith line is linked to one
Boot line (1.4.x ↔ Boot 3.5.x, 2.1.x ↔ Boot 4.1.x, 2.2.x ↔ Boot 4.2.x), so in practice it
follows Boot's window.

### What changes for our design (checked in the sources)

| Topic | Spring AI 1.1.8 | Spring AI 2.0.1 | Effect |
|---|---|---|---|
| Prompt caching (Q-2) | `api.AnthropicCacheOptions`; strategies `NONE`, `TOOLS_ONLY`, `SYSTEM_ONLY`, `SYSTEM_AND_TOOLS`, `CONVERSATION_HISTORY`; TTL 5m/1h | Same strategies and TTLs, moved to `org.springframework.ai.anthropic`, plus `cacheToolResults` | **Q-2 passes on both** |
| Anthropic client | Spring's own HTTP client (`AnthropicApi`) with `spring-ai-retry` | Official `anthropic-java-core` 2.52.0 SDK over OkHttp | Different bean wiring in 5a |
| Default temperature (Q-1, T-8) | Always sends 0.8 | Not sent unless set | Removes the T-8 trap |
| `effort` (T-6) | Not available | `outputConfig` (`OutputConfig.effort`: low, medium, high, xhigh, max); `ThinkingConfigAdaptive` exists | Resolves T-6 |
| Default max tokens | 500 | 4,096 | Set explicitly either way (llm-architecture.md §1) |
| **Retries** | Spring AI `RetryTemplate` | **The SDK retries twice by default** (`AnthropicSetup.DEFAULT_MAX_RETRIES = 2`, 60 s timeout) | See decision 3 |
| Cache tokens in usage (Q-17) | Only in the native usage object | `cacheRead` / `cacheWrite` passed into `DefaultUsage` | Simpler cost metering |
| Tool-call cap | Not built in | `DefaultToolCallingManager` with `ToolCallLimits` (defaults 40 per tool, 150 per turn, `THROW`) | Our cap of 8 per turn can use it (llm-architecture.md §9) |

Platform versions managed by `spring-boot-dependencies` change too:

| | Boot 3.5.16 | Boot 4.1.1 |
|---|---|---|
| Jackson | 2.21 (`com.fasterxml.jackson`) | **3.1** (`tools.jackson`). Jackson 2.21 is still managed |
| Hibernate | 6.6 | **7.4** |
| Spring Security | 6.5 | **7.1** |
| Flyway | 11.7 | **12.4**. Needs `spring-boot-starter-flyway`, because Boot 4 splits auto-configuration into modules |
| Testcontainers | 1.21 | **2.0** (artifact names changed) |
| PostgreSQL JDBC | 42.7.11 | 42.7.13 |

## Options

- **A. Boot 3.5.16 + Spring AI 1.1.8 + Modulith 1.4.13.** More community examples, and
  the Phase 2 checks were done against it. But free (OSS) support ended on 2026-06-30, so
  fixes, including security fixes, now need a commercial subscription. It also keeps T-6
  and T-8.
- **B. Boot 4.1.1 + Spring AI 2.0.1 + Modulith 2.1.1.** Supported until 2027-07-31. It
  resolves T-6 and T-8 and simplifies Q-17. It has fewer examples and several
  major-version platform changes.
- **C. Boot 4.2 / Spring AI 2.1 milestones.** Rejected: not GA.

## Decision

1. **Stack B:** Spring Boot **4.1.1**, Spring AI **2.0.1** (BOM), Spring Modulith **2.1.1**
   (BOM), Java 21. SPEC §3 and AGENTS.md now say "Boot 4.x, Spring AI 2.x".
2. **Verify every API against the pinned jars.** Boot 4, Spring AI 2 and Jackson 3 are new.
   Imports and APIs are never written from memory; package names are checked against the
   pinned jars (AGENTS.md).
3. **Anthropic SDK retries = 0.** Every Anthropic `ChatModel` bean is built with
   `AnthropicChatOptions.builder().maxRetries(0)` (or `spring.ai.anthropic.max-retries=0`
   if auto-configuration is ever used). **Resilience4j is the only retry layer**
   (llm-architecture.md §11, ADR-005). Otherwise one failed call could become 3 SDK
   attempts × N Resilience4j attempts. That would multiply load on the provider during a
   429 storm, blow the latency budget before the fallback starts (NFR-14), and hide the
   real failure count from the circuit breaker. The SDK timeout is also set explicitly,
   below the Resilience4j time limiter, so the time limiter decides when to fall back. The
   actual values are chosen in 5a. A unit test in 5a asserts that the built options carry
   `maxRetries = 0`.
4. **Upgrade path:** move to **Boot 4.2 / Spring AI 2.1 / Modulith 2.2 once all three are
   GA**. Boot 4.2's first release is planned for 2026-11-30, and 4.1.x OSS support ends on
   2027-07-31. The move is its own change, gated like a phase:
   - Re-run the §2 source checks in llm-architecture.md, including prompt caching.
   - `./mvnw verify` must pass.
   - Run the LLM eval suite, only with the owner's approval.
   - Do it after the MVP slice (6a), not in the middle of a phase.
   Patch releases within 4.1.x / 2.0.x are taken as they come, after `./mvnw verify`.

## Consequences

- Positive: OSS support covers the MVP slice and the remainder, with at least 10 months
  left today. T-6 and T-8 are closed. Cost metering reads cache tokens directly. There is
  a built-in tool-call limit.
- Positive: one retry layer makes the retry count, latency budget and circuit breaker
  behaviour predictable.
- Negative: more "verify before use" work (Jackson 3, Hibernate 7, Security 7,
  Testcontainers 2). This is covered by the AGENTS.md rule.
- Negative: the Phase 2 checks against 1.1.8 had to be repeated. The results are in
  llm-architecture.md §2 (2026-09-25).
- Negative: an upgrade to 4.2 / 2.1 is planned within about a year. The upgrade path
  above keeps it deliberate.
