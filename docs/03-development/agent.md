# Agent (Phase 5a)

| | |
|---|---|
| Status | **For review at the 5a gate** (design note and code reviewed together) |
| Date | 2026-09-25 |
| Spec reference | SPEC.md §2.6, §4.3–§4.8, §11 (5a); plan-and-budget.md §2a |
| Related | [llm-architecture.md](../02-design/llm-architecture.md), [security.md](../02-design/security.md) §2, §6, [nfr.md](../02-design/nfr.md) NFR-01a/b, ADR-003, ADR-005, ADR-008, [deterministic-core.md](deterministic-core.md), [assumptions.md](../01-requirements/assumptions.md) A-96 to A-108 |

This note defines the Phase 5a agent: the chat bean, the read-only tools, the turn
orchestrator with the `diffBills` pre-fetch, the grounding gates, the fallback templates,
chat memory, cost metering, security and the SSE API. Owner answers from the 5a planning
round are marked **(5a Q-xx)**.

**5a answers (owner, 2026-09-25):**
- **Q-32:** Haiku-tier model: desk research only in 5a (§12); evals and wiring in 5b.
- **Q-33:** `ToolCallAuditor` hook now; its implementation comes in 6a.
- **Q-34:** gate each sentence before it is sent. A `reset` event is used only when a
  regeneration is needed after sentences were already sent.
- **Q-35:** `bill_diagnosis` table, created by editing V4 in place.
- **Q-A:** no starter. Spring AI observability is wired explicitly and tested.
- **Q-F:** the full free-text scrubber is built in 5a.
- **Live checks:** allowed in 5a if the owner approves each command; under $2 in total. **Deferred** (no API credits yet); commands are in PROGRESS.md.

---

## 1. Spring AI 2.0.1 facts checked for 5a (sources jars, 2026-09-25)

Every class and method used in 5a was read in the sources jars resolved by `./mvnw`
(`spring-ai-anthropic`, `-model`, `-client-chat`, `-commons` 2.0.1;
`com.anthropic:anthropic-java-core` 2.52.0; `spring-security-core` 7.1.1). The table
continues llm-architecture.md §2 (F-1 to F-9).

| # | Fact | Where | Design impact |
|---|---|---|---|
| F-10 | **`AnthropicChatModel` no longer runs tools.** `call`/`stream` return the model's `tool_use` blocks as `AssistantMessage.getToolCalls()`. The tool loop now lives in the ChatClient advisor `ToolCallingAdvisor` (new in 2.0.0), which `DefaultChatClient.autoRegisterToolCallingAdvisor()` adds unless the advisor param `AdvisorParams.toolCallingAdvisorAutoRegister(false)` is set | `AnthropicChatModel.internalStream` (aggregates only), `ToolCallingAdvisor`, `DefaultChatClient` | **The orchestrator runs the tool loop itself** (§4). Auto-registration is switched off. Reasons: see F-11 and F-12 |
| F-11 | In streaming mode, `ToolCallingAdvisor` runs the tools **on `Schedulers.boundedElastic()`**. Only the Reactor `ContextView` is handed over (`ToolCallReactiveContextHolder`); the thread-local `SecurityContext` is not. *(Corrected at the gate: `spring-security-core` 7.1.1 does ship `SecurityContextHolderThreadLocalAccessor`, registered for Micrometer context propagation. An earlier jar search used the wrong class name. See §4.2 for why that option was not taken)* | `ToolCallingAdvisor.handleToolCallRecursion`; `spring-security-core` jar listing | Tools read the account from the `SecurityContext` (SPEC §4.3 rule 2). Our own loop executes tools on the turn's thread, which carries the `SecurityContext` (§7) |
| F-12 | `ToolCallingAdvisor` sums usage over all rounds (`UsageAccumulator`) and filters out the tool-call rounds. Each round's own usage is only available per `ChatResponse` | `ToolCallingAdvisor`, `UsageAccumulator`; `AnthropicChatModel` (`message_start` input tokens, `message_delta` output and cache tokens) | Our loop reads each round's usage and writes one `llm_call_log` row per round trip (llm-architecture.md §12) |
| F-13 | `AnthropicChatOptions.builder()`: `model(String)`, `maxTokens`, `apiKey`, `baseUrl`, `timeout(Duration)`, `maxRetries(Integer)`, `cacheOptions`, `effort(OutputConfig.Effort)`, `thinkingAdaptive()`, `thinkingDisabled()`, `toolCallbacks`, `toolContext`. The timeout is also sent per call as `RequestOptions` (`requestOptionsFor`); temperature/top_p/top_k are added to the request only when non-null. There is **no `internalToolExecutionEnabled`** any more | `AnthropicChatOptions`, `DefaultToolCallingChatOptions.Builder`, `AnthropicChatModel.createRequest` | The bean sets model, max tokens, timeout, `maxRetries(0)` and the key; temperature stays unset (Q-1) |
| F-14 | **Cache breakpoints are recomputed on every request** (`createRequest` builds a new `CacheEligibilityResolver` per call). With `CONVERSATION_HISTORY`: system text (breakpoint 1), the **last user message** (2), with `cacheToolResults(true)` the **last tool result** (3), and the last tool definition (4). The default minimum content length is 1 character for every message type; the default TTL is 5 min | `CacheEligibilityResolver`, `AnthropicCacheOptions`, `createRequest` | llm-architecture.md §5 "verify breakpoints on every tool round": **confirmed in code**, and asserted by the fake-API test (§10). The live check measures the cache-read share |
| F-15 | `DefaultToolCallingManager.executeToolCalls(prompt, chatResponse)` executes **every** tool call of the first generation that has tool calls, counts limits from the prompt's history (`ToolCallLimits.countPriorToolCalls`), wraps each call in a `spring.ai.tool` observation, and passes `ToolContext` from `ToolCallingChatOptions.getToolContext()` | `DefaultToolCallingManager` | `TurnToolBudget` decides per call, passes only the admitted calls to the delegate, and adds a synthetic result for each call it refuses (§5) |
| F-16 | `DefaultToolExecutionExceptionProcessor` by default returns the exception message to the model (`alwaysThrow=false`) | `DefaultToolExecutionExceptionProcessor.Builder.alwaysThrow` | Set `alwaysThrow(true)`: an unexpected tool error never leaks internals to the model; the turn falls back to the template. Expected conditions (no bill, BSS down) are normal tool results |
| F-17 | `MethodToolCallbackProvider.builder().toolObjects(...)` builds callbacks from `@Tool` methods; `@Tool(name, description, returnDirect, resultConverter)`, `@ToolParam(required, description)`; results are serialised by `DefaultToolCallResultConverter` via `JsonHelper` | `org.springframework.ai.tool.*` | Tool classes are plain beans; results are records |
| F-18 | `ChatMemoryRepository.saveAll(conversationId, messages)` means "replace the conversation with these messages" | `ChatMemoryRepository` | **Deviation from llm-architecture.md §8:** the append-only store does not implement `ChatMemoryRepository`, because an append-only `saveAll` would break its contract. The orchestrator uses our `ConversationStore` directly (no memory advisor) |
| F-19 | Observation names: `gen_ai.client.operation` (chat model, streaming or call), `spring.ai.chat.client`, `spring.ai.advisor`, `spring.ai.tool`. `ChatClient.builder(chatModel, observationRegistry, null, null)`, `AnthropicChatModel.builder().observationRegistry(...)`, `DefaultToolCallingManager.builder().observationRegistry(...)` | `…observation.conventions`, builders | Without the starter, all three get the Boot `ObservationRegistry` explicitly **(5a Q-A)**; a test checks that the meters are recorded (§10) |
| F-20 | `AnthropicSetup`: a null `apiKey` falls back to the `ANTHROPIC_API_KEY` environment variable; defaults are a 60 s timeout and 2 retries | `AnthropicSetup.buildClientOptions` | The key is always passed explicitly from `ANTHROPIC_API_KEY_CHAT`; if it is empty in `LLM` mode, startup fails, so the wrong workspace key is never picked up |
| F-21 | `DelegatingSecurityContextExecutorService` exists in `spring-security-core` 7.1.1 (`org.springframework.security.concurrent`) | jar listing | The SSE turn runs on a virtual-thread executor wrapped with it (§7) |

## 2. Where the code lives

| Module | Public API | Internal |
|---|---|---|
| `domain` | `BillShockDiagnosis`, `UuidV7`, `InrFormat.gst` | — |
| `security` | `CurrentCustomer`, `PiiScrubber`, `MsisdnMask`, `DemoUserProperties` | `SecurityConfiguration` (filter chain, in-memory users) |
| `bss` | `BillingReadModel.account(...)` (masked-MSISDN source) | — |
| `audit` | `ToolCallAuditor` (hook, **5a Q-33**), `NoOpToolCallAuditor` | 6a replaces the no-op |
| `tools` | `BillingTools`, `UsageTools`, `CatalogTools`, result records, `Amount`, `Untrusted` | — |
| `agent` | `ChatOrchestrator`, `ChatEvent`, `ChatTurn`, `LlmProperties`, `ToolCallBudget` (4a) | `TurnToolBudget`, `GroundingGate`, `SentenceSplitter`, `FallbackTemplates`, `DiagnosisGate`, `ConversationStore`, `CostMeter`, `LlmConfiguration`, `DiagnosisTool` |
| `api` | `ChatController`, `ProblemDetailsAdvice` | — |

The allowed dependencies in each `package-info.java` stay as they are.

## 3. The chat bean (llm-architecture.md §1, ADR-008)

- `AnthropicChatModel.builder()` with `AnthropicChatOptions`:
  - `model` = `billshock.llm.chat.model` (`claude-sonnet-5`)
  - `maxTokens` = 1024
  - `timeout` = 30 s (config)
  - **`maxRetries(0)`**
  - `apiKey` = `${ANTHROPIC_API_KEY_CHAT}`
  - `baseUrl` only when configured (tests)
  - **no temperature** (Q-1)
  - cache options: `CONVERSATION_HISTORY`, `cacheToolResults(true)`, TTL 5 min
  - `effort` and thinking are config (`billshock.llm.chat.effort`, `…thinking`); both are unset by default until the live check (§11) measures them (T-6)
- `toolCallingManager` = the shared `DefaultToolCallingManager` bean (it is used only for
  `resolveToolDefinitions`, F-10). `observationRegistry` = the Boot registry.
- `ChatClient.builder(chatModel, observationRegistry, null, null).build()` is the
  `chatClient` bean. Every request sets `toolCallingAdvisorAutoRegister(false)` (F-10).
- **Mode** `billshock.llm.mode` = `LLM` | `TEMPLATE_ONLY` (ADR-005 kill switch). In
  `TEMPLATE_ONLY` no chat model is called and no key is needed. In `LLM` mode an empty key
  stops startup (F-20).

## 4. A chat turn

```
POST /api/v1/chat {conversationId?, message}  (Basic auth, SSE)
 1. account = CurrentCustomer.require(); message = PiiScrubber.scrub(message)
 2. conversation: new (UUIDv7, OPEN) or load — another account's id → 404 (not 403)
 3. first turn only: diff = BillDiffEngine.diff(account, latest bill)     [Java]
      → SSE summary (template line)                                       NFR-01a
      → store a SYSTEM_CONTEXT message: the diffBills tool result JSON
 4. mode TEMPLATE_ONLY or cost cap reached → template answer (§8), done
 5. messages = [system v1] + window(last 12) + [user]; persist the USER message
 6. round loop (≤ 9 round trips):
      stream a round via chatClient (tools registered, no advisor)
        text chunks → SentenceSplitter → GroundingGate → SSE token per sentence
        tool calls  → collected; usage → CostMeter (llm_call_log row)
      tool calls? → SSE progress; TurnToolBudget.executeToolCalls(...) on this thread
                   → the results are added to the grounding context
                   → LIMIT_REACHED → template answer + hand-over line, stop
      no tool calls → end
 7. DiagnosisGate: recordDiagnosis input vs engine → bill_diagnosis row; SSE diagnosis
 8. persist ASSISTANT (released text) + SYSTEM_CONTEXT digest of this turn's tool results
 9. SSE done {conversationId, promptVersion}
```

- **The pre-fetch runs on the first turn of every conversation**, for the latest bill (A-96).
  The context message uses the same JSON as the `diffBills` tool, so the model sees one
  format. It is a message, never part of the system prompt (llm-architecture.md §5).
- **Message building:** the history is loaded in order. A SYSTEM_CONTEXT message becomes a
  `UserMessage` wrapped as `<server_context>…</server_context>`. Adjacent user-side messages
  are merged into one `UserMessage`, so roles alternate. The system prompt is the rendered
  `system.v1.st`, the same for every customer.
- **Round limit:** at most 9 LLM round trips per turn (llm-architecture.md §9); a 10th →
  template answer.
- **Input-token budget (30,000 per call):** checked after each round from the reported usage
  (input + cache read + cache write). If it is exceeded, no further round is sent and the turn
  ends with the template answer (A-100).
- **Text before a tool call** (for example "Let me check your usage.") goes through the gate
  like any other sentence and is shown. The prompt asks the model to keep it to one short
  sentence.

### 4.1 SSE events

| Event | Data (JSON) | When |
|---|---|---|
| `summary` | `{conversationId, text}` | First turn, before the first LLM call (NFR-01a) |
| `progress` | `{text}` | Before each tool round, for example "Checking your usage details" (the fixed wording per tool; never model text) |
| `token` | `{text}` | One per **gated sentence** (**5a Q-34**) |
| `reset` | `{reason}` | Only when a regeneration happens after sentences were already sent: the client discards the draft answer |
| `fallback` | `{reason, text}` | The template answer replaces the LLM answer |
| `diagnosis` | `BillShockDiagnosis` display form | After the answer |
| `error` | `{code}` | Unrecoverable error (the stream then ends) |
| `done` | `{conversationId, promptVersion}` | Always last |

A comment heartbeat is sent every 15 s. `actions` arrives in 6a.

### 4.2 Why the orchestrator runs the tool loop (gate review, item 1)

**Option chosen:** our own loop, with one streamed `ChatClient` call per round. Rejected:

| Option | What it would take | Why it was rejected |
|---|---|---|
| **A. `ToolCallingAdvisor` + Spring AI `ToolContext` for identity** (the server puts the account id into the tool context; the model can never set it) | Every tool method takes a `ToolContext` parameter and reads the account from it | **Just as safe against the model.** The model cannot write the tool context, so SPEC §4.3 rule 2's intent would hold. But it has three costs. (1) It breaks the one identity rule used by every other module and by 6a's guardrails ("the account comes from the SecurityContext"), so there would be two sources of identity. (2) Each tool would need an extra parameter. (3) It solves only identity, not the points under C |
| **B. `ToolCallingAdvisor` + SecurityContext propagation** (`SecurityContextHolderThreadLocalAccessor` exists in Spring Security 7.1.1) | `Hooks.enableAutomaticContextPropagation()`, plus capturing the context on subscription | It is a **global** Reactor hook: it changes the thread-local behaviour of every reactive pipeline in the application (including the Anthropic SDK client), which is a broad side effect for one feature. Its behaviour on the advisor's `boundedElastic` tool execution was **not verified**, and a silent failure would be a security bug. It also solves only identity |
| **C. Keep the advisor and subclass it** for the other needs | A per-request advisor with a `TurnToolBudget` manager; the `doAfterStream` / `doBeforeStream` hooks for metering and stopping | See the point-by-point check below |

**Point-by-point: what genuinely needs the custom loop.** Your condition was "keep it if
sentence gating and the budget genuinely need it". Neither does:
- **Sentence gating:** does **not** need the loop. It works on the text stream, and the
  advisor streams every round's text too.
- **Tool budget:** does **not** need the loop. `ToolCallingAdvisor.builder().toolCallingManager(...)`
  accepts a per-request `TurnToolBudget`.
- **Per-round metering:** possible with the advisor, through a `doAfterStream` override
  (the advisor adds usage across rounds, but each round's response passes through that hook).
- **Stopping between rounds with a template answer** (cost cap, round limit, input-token
  budget): possible with the advisor, by throwing from a `doBeforeStream` override. Only
  `ToolCallLimitExceededException` is handled by the advisor itself; anything else ends the
  stream as an error, which the orchestrator would map to a fallback.
- **Regenerating only the answer round** (label mismatch, customer amount): **needs the loop.**
  The advisor does not return the turn's intermediate history (assistant tool calls + tool
  results). A regeneration would re-run the whole turn, including every tool call and LLM
  round. That costs up to about 8 more LLM calls per regeneration and adds seconds of
  latency, which puts NFR-02 at risk.
- **Identity:** needs A or B above, each with the costs described.

**Conclusion:** the loop is kept because of targeted regeneration and the single identity
rule, not because of gating or the budget. It is about 120 lines in
`DefaultChatOrchestrator`, fully covered by `ChatApiIT`. **Revisit** when moving to Spring
AI 2.1 (ADR-008): if the advisor exposes the turn history or a per-round hook API, move to
option C.

## 5. Tool-call budget: `TurnToolBudget` (Q-29)

- Implements `ToolCallingManager` and delegates to the shared `DefaultToolCallingManager`.
  There is one instance per turn, holding a new `ToolCallBudget(8, {escalateToHuman,
  recordDiagnosis})`.
- `executeToolCalls(prompt, response)`: for each tool call, in order, it asks
  `ToolCallBudget.admit(name)`:
  - `ALLOWED` → the call is passed to the delegate.
  - `ALREADY_CALLED_THIS_TURN` → the call is not executed. The synthetic result is
    `{"status":"ALREADY_DONE_THIS_TURN"}`, and the turn continues.
  - `LIMIT_REACHED` → throws `ToolBudgetExhaustedException`. The orchestrator stops the
    loop and answers with the template plus the hand-over line (the real `escalateToHuman`
    tool arrives in 6a).
- The delegate receives an `AssistantMessage` with only the admitted calls. The results
  (real and synthetic) are merged back in the model's original order, and the history is
  rebuilt with the **original** assistant message, so every `tool_use` id gets a result.
- **Backstop** on the delegate: `maxTotalToolCalls(10)`, `maxCallsPerTool("recordDiagnosis", 1)`,
  `maxCallsPerTool("escalateToHuman", 1)`, `THROW`. A `ToolCallLimitExceededException` is
  treated the same as `LIMIT_REACHED`. Edge case: the built-in counter also counts refused
  duplicates that are still in the history, so a turn with 8 counted calls plus 3
  `recordDiagnosis` attempts trips the backstop. That ends in the same safe outcome.
- **Audit hook (5a Q-33):** after each executed or refused call, the budget calls
  `ToolCallAuditor.toolCalled(conversationId, toolName, maskedArguments, resultSha256,
  outcome)`. 5a uses the no-op bean; 6a writes `audit_events`.

## 6. Tools

**No tool parameter names an account, customer or MSISDN.** Every tool calls
`CurrentCustomer.require()`. A reflection test enforces this over every `@Tool` method.
Tool descriptions contain no data, dates or thresholds, so the cached prefix stays stable.

**Result conventions:**
- Every amount is an `Amount {value: "2094.50", display: "₹2,094.50 incl. GST"}`, formatted
  by `InrFormat`. The labels are `incl. GST`, `excl. GST`, `GST` (the tax component) and
  `+ GST` (catalogue prices).
- Untrusted text (line-item descriptions, product names, provider names) is sanitised
  (control characters removed, at most 100 characters) and returned under `untrusted`.
- Periods are `YYYY-MM`. An optional period means the latest bill.
- A missing bill or an invalid period is a normal result `{found:false, message}`. It is
  not an exception.

| Tool | Parameters | Returns |
|---|---|---|
| `getBillSummary` | `billPeriod?` | period, bill date, service period, masked MSISDN, subtotal (excl.), GST, total (incl.), supply type |
| `getBillHistory` | `months?` (1–6, default 6) | newest first: period, bill date, total (incl.) |
| `diffBills` | `currentPeriod?`, `baselineMonths?` (1–6, default 3) | verdict, current/baseline totals (incl.), baseline bill count, total excess (incl.), excess %, causes (group, excl./GST/incl., lineItemIds), findings. Same JSON as the pre-fetch |
| `getLineItems` | `billPeriod?`, `category?` | lineItemId, category, `untrusted.description`, amount (`excl. GST`, or `GST` on TAX lines), quantity, unit, service period, subscriptionId |
| `getUsageDetails` | `billPeriod?`, `type` (DATA, VOICE, SMS, ROAMING) | period aggregates from the read model. ROAMING: per country (dates, MB, min, SMS, charge excl. GST). DATA/ROAMING also get a per-day breakdown from `UsageGateway` (BSS); if BSS is down, `detailAvailable:false` and the aggregates are still returned |
| `getActiveSubscriptions` | — | plan, add-ons, VAS: subscriptionId, type, code, `untrusted.name/provider`, price `+ GST`, activation date and channel, opt-in evidence (`doubleOptIn`, steps, or `MISSING`) |
| `searchPlanCatalog` | `type?` (PLAN, ADD_ON), `maxMonthlyPriceInr?`, `minDataGb?`, `roamingBand?` (A-98) | plans (rental `+ GST`, allowances) and add-ons (price `+ GST`, contents, validity, band) valid today |
| `simulatePlans` | `billPeriod?` | status; plan options with **`newBill` and `saving` as two separately labelled amounts** (owner rule, 4a gate), e.g. `PP_499`: newBill `₹588.82 incl. GST`, saving `₹317.00 incl. GST`; best add-on likewise; `RECENT_PLAN_CHANGE` with the date; `NOT_SIMULATABLE` with the reason |
| `recordDiagnosis` (agent) | `causes[] {group, amountInclGst}`, `totalExcess`, `confidence`, `recommendedActions[] {type, code?, summary}` | `{"status":"RECORDED"}`. Amounts are copied display strings; the gate checks them (§8.2) |

`newBill` is `PlanOption.totalInclGst`: the plan-dependent charges re-rated, with GST (A-93).
For the seed accounts it equals the whole bill, because VAS and duplicates are excluded
(A-99).

## 7. Security

- **Demo users (5a):** `cust1001` … `cust1006`, role `CUSTOMER`, each mapped to its account
  in config (`billshock.security.demo-users`). There is one password from the environment
  variable `DEMO_USER_PASSWORD` (A-97); the test profile sets its own value. HTTP Basic,
  stateless, CSRF off for `/api/**` (no cookies are issued). Actuator: `health` only.
- **`CurrentCustomer.require()`** reads the `account_id` stored on the authenticated
  principal. Anything else throws `AccessDeniedException`.
- **Threading:** the controller returns an `SseEmitter`. The turn runs on a virtual-thread
  executor wrapped in `DelegatingSecurityContextExecutorService` (F-21). The whole tool loop
  runs on that thread, so tools always see the caller's `SecurityContext` (F-11).
- **Another account's conversation id** → 404, identical to an unknown id.
- **`PiiScrubber` (5a Q-F, security.md §6.2):**
  - 10-digit Indian mobiles and `+91`/`91`/`0` forms → `[PHONE]`
  - e-mail → `[EMAIL]`
  - 12-digit Aadhaar-like numbers (also 4-4-4 groups) → `[ID]`
  - 13–19-digit Luhn-valid card numbers → `[CARD]`
  - IFSC codes and 9–18-digit account-like numbers next to "account/a/c" → `[BANK]`
  Applied to the customer message **before** it is stored or sent. Amounts such as
  `₹2,094.50` and periods are not touched (tests).
- **`MsisdnMask`:** `+915550001001` → `******1001`. Tools only ever return the masked form.

## 8. Grounding gates, templates and the diagnosis

### 8.1 Sentence gate (5a Q-34)

- `SentenceSplitter` buffers the stream and releases a sentence at `.`, `!` or `?` followed by
  whitespace, and at blank lines and line-starting list markers. It never splits inside an
  amount (`₹2,094.50`) or after `incl.`, `excl.`, `e.g.`, `i.e.`, `Rs.`, `approx.`, `vs.`, `No.`.
- **Allowed amounts** = every amount token found in this turn's tool results, in the
  pre-fetch context and in the SYSTEM_CONTEXT digests in the memory window. An amount token
  is `₹<number>` with an optional label (`incl. GST`, `excl. GST`, `GST`, `+ GST`).
- For each currency amount in a sentence (`₹`, `Rs`, `Rs.` or `INR` followed by a number):
  - the number is in no allowed token → **value violation**
  - the number is allowed but the written form (symbol and label) is not identical to an
    allowed token → **label mismatch**; a missing label counts as a mismatch (Q-23)
- **Customer amount** (gate review, A-106): the number is in no allowed token but the
  customer typed it (`₹3,000`, `Rs 5,000`, `5000 rupees`, `750/-`) in a message in the window
  → treated like a label mismatch: regenerate once, with a correction note ("do not repeat the
  customer's amounts"). A second failure → fallback reason `CUSTOMER_AMOUNT`. Counted as
  `grounding_violation_total{type=customer_amount}` and
  `chat_fallback_total{reason=CUSTOMER_AMOUNT}`. The system prompt also tells the model not to
  repeat customer amounts and never to confirm a promised credit.
- **Value violation** → the stream is cancelled; SSE `fallback` with the template answer.
- **Label mismatch** → the answer round is regenerated **once**. The same messages are sent
  again, plus a short corrective user note ("copy every amount string exactly…"). If
  sentences were already sent, `reset` is sent first. A second failure → `fallback`.
- **Prompt-leak gate:** a sentence containing the canary string from the system prompt is
  blocked, and the answer falls back. Guardrail thresholds are never given to the model in
  5a, so they cannot leak, and any ₹ threshold is already blocked by the value rule.
- **Action-claim gate:** a sentence that claims an action was done ("I have credited …",
  "has been cancelled/refunded …") is replaced by "I haven't changed anything on your
  account; any change needs your confirmation first." In 5a no action could have run. **6a:** a
  claim passes only when every effect it names (credit, refund, unsubscribe, barring, plan change,
  add-on, dispute, escalation) has a `DONE` execution step in this conversation; see
  [actions.md](actions.md) §12 and §13 item 6.
- Counters: `grounding_violation_total{type=value|label|leak|action_claim}`.

### 8.2 Diagnosis gate and `bill_diagnosis` (5a Q-35)

- `recordDiagnosis` input: causes (group + `amountInclGst` display string), `totalExcess`,
  `confidence` (HIGH, MEDIUM, LOW), and `recommendedActions` (type from a fixed enum,
  optional plan/add-on code, a one-line summary).
- The gate accepts the LLM version only if:
  - the set of cause groups equals the engine's causes
  - each amount string equals the engine's `inclGst` display
  - `totalExcess` equals the engine's display
  - every recommended plan/add-on code exists in the catalogue
  Otherwise the **engine-built** diagnosis is stored (causes and excess from `BillDiff`,
  confidence HIGH, recommended actions from the simulator), and the violation is counted.
- It is stored in `bill_diagnosis` (V4, edited in place): account, conversation, bill period,
  verdict, total excess `NUMERIC(14,2)`, causes `jsonb` with the engine's `Money` values,
  confidence, recommended actions `jsonb`, `source` (LLM or ENGINE), prompt version,
  created_at. Money in the row always comes from the engine.

### 8.3 Fallback templates (Template Method)

- `FallbackTemplates` renders from `BillDiff` plus the optional `PlanSimulation`.
  - **Summary line (NFR-01a):**
    - `MEANINGFUL_INCREASE`: "Your {period} bill is {totalExcess} higher than your recent
      average, mainly because of {group} charges of {cause.inclGst}."
    - `NORMAL`: "Your {period} bill of {total} is in line with your recent bills."
    - `INSUFFICIENT_HISTORY`, and "no bill found", each have a line of their own.
  - **Full answer:** the summary, one line per cause and per finding (duplicate, new
    subscription, plan-change proration), and one recommendation line if the simulator has
    a saving option (with new bill and saving shown separately).
  - **Hand-over line:** used when the budget or the cost cap stops the turn.
- The files are `templates/fallback/*.en.st`, one per cause group and verdict, rendered
  with StringTemplate (`spring-ai-template-st` is already on the classpath). Every amount
  comes from `InrFormat`, so the fallback follows the same GST-label rule.

## 9. Memory and cost

- **`ConversationStore`** (JdbcClient; owns V4):
  - `conversation` rows
  - append-only `chat_messages` (next `seq` = max + 1 within the conversation's month
    partition, which is derived from the UUIDv7 timestamp)
  - the window: the last 12 USER/ASSISTANT/SYSTEM_CONTEXT messages
  - `TOOL` rows are not written in 5a: tool results live only inside a turn, and a
    SYSTEM_CONTEXT digest carries their amounts forward (A-101)
- **`CostMeter`:** one `llm_call_log` row per round trip:
  - input, cache write and cache read tokens, output tokens, latency, outcome
  - `cost_usd = Σ tokens × price per MTok ÷ 1,000,000`, in `BigDecimal`, stored at scale 6
    HALF_EVEN (Q-17)
  - also added to `conversation.llm_cost_usd`
  - cache writes are priced at the 5-min rate, because the TTL is 5 min
  - prices come from `billshock.llm.pricing.<model>.*`; a model with no price entry fails
    at startup
- **Cost cap:** before each round, `llm_cost_usd × fx` (A-36, ₹88/USD) ≥ ₹45 → the
  conversation switches to templates for its remaining turns (NFR-09).

## 10. Tests

- **Unit tests:**
  - every tool on the seed fixtures, with exact display strings
  - the reflection rule: no identity parameter on any `@Tool` method
  - `TurnToolBudget`: 9th call, duplicate `recordDiagnosis`, mixed batches, order of results
  - `SentenceSplitter`, `GroundingGate` (value, label, missing label, `Rs`, canary, action claim)
  - `FallbackTemplates` for all 6 scenarios
  - `DiagnosisGate`
  - `PiiScrubber` and `MsisdnMask`
  - `CostMeter` with exact BigDecimal results
  - `UuidV7` and the partition month
  - the chat bean's options: `maxRetries` 0, timeout, no temperature, cache strategy
- **Fake Anthropic API (integration tests):** a local HTTP server that speaks the Messages
  streaming protocol, with scripted responses and recorded requests. The **real**
  `AnthropicChatModel` bean runs against it through `baseUrl`. The ITs cover:
  - **SSE order and summary latency:** `summary` arrives first, and in under 1.5 s.
  - **Tool wiring:** the fake model asks for `getBillSummary`/`simulatePlans`, and the result
    is the logged-in account's own data.
  - **Cross-account attempt:** user 1001 names account 1002 in the message and still gets
    only 1001's data. A conversation id owned by another account returns 404.
  - **Request body checks** on every recorded request:
    - no `temperature` field
    - `cache_control` on the system text, the last user message, the last tool result
      and the last tool
    - `max_tokens` is 1024
  - **Fallback triggers:** a value violation, a 5xx from the API, a timeout and the 9th tool
    call each lead to a `fallback` event.
  - **Regeneration:** after a label mismatch the answer is regenerated, and a `reset` event
    is sent only when sentences were already out.
  - **Persistence:** append-only memory rows, `llm_call_log` and cost rows,
    `bill_diagnosis`.
  - **Observations (5a Q-A):** `gen_ai.client.operation`, `spring.ai.chat.client` and
    `spring.ai.tool` meters are recorded.
  - **Scenario 1006:** no invented issue; the verdict stays `NORMAL`.

## 11. Live checks (**deferred — run when API key is available**; exact commands in PROGRESS.md, Phase 5a)

1. **Q-1 temperature:** one short request to `claude-sonnet-5` without `temperature`, then
   one with `temperature=0.2`. The expectation is 200, then 400.
2. **Caching:** one scenario conversation (1001), two turns. Every round's `cache_read`,
   `cache_write` and `input` are recorded, and the cache-read share is computed
   (NFR-21 ≥ 60%).
3. **T-6:** the same turn once with `effort=medium`, to compare output tokens and latency
   against the default (`high`, models overview).

Each command is shown to the owner before it runs. The key is loaded only by
`set -a; source .env; set +a` inside that command. Token usage and cost are reported after
each check.

## 12. Haiku-tier model: desk research (Q-15, 5a Q-32)

Sources, retrieved 2026-09-25:
- Anthropic *Models overview*: <https://platform.claude.com/docs/en/about-claude/models/overview>
- *Model deprecations*: <https://platform.claude.com/docs/en/about-claude/model-deprecations>

| §15 step | Finding |
|---|---|
| 1. Candidate | **`claude-haiku-4-5-20251001`** (alias `claude-haiku-4-5`) is the **only** Haiku-tier model in the current line-up. The other current models are Sonnet 5 ($2/$10), Opus 5.5 ($4/$20) and Fable 5.1 ($10/$50). There is no newer Haiku model to choose |
| 2. Compatibility | Price $1 input / $5 output per MTok (matches A-20). 200K context, 64K max output. **Extended thinking** (manual budget), **no `effort` support**. Sampling parameters: the 400-on-non-default rule applies to "Claude 4.7 and later" models, so Haiku 4.5 still accepts `temperature` (0.2 stays valid). Tool use supported. The prompt-cache minimum of 4,096 tokens (T-7) is unchanged |
| Lifecycle | Status **Active**, not deprecated; retirement "not sooner than October 15, 2026" (Anthropic platforms). Anthropic gives **at least 60 days' notice** before retirement, and no notice has been published, so the earliest possible retirement is about **24 Nov 2026** (A-102). Bedrock and Vertex set their own dates |
| Decision for 5b | Use `claude-haiku-4-5-20251001` as the proactive model. **Risk R-21 is close:** a successor is likely before Phase 7, so the 5b eval suite must be runnable against any model id from config. Re-check both pages at the start of 5b |

## 13. Deviations and choices to confirm at the gate

1. **Our own tool loop instead of `ToolCallingAdvisor`** (F-10 to F-12). `ChatClient` is still
   the bean, and its observation still runs. Accepted at the gate; the rejected alternatives are in §4.2.
2. **The memory store is not a `ChatMemoryRepository`** (F-18).
3. **`recordDiagnosis` lives in `agent`**, not `tools`, because it writes turn state.
4. **Unexpected tool errors are thrown, not returned to the model** (F-16).
5. **`bill_diagnosis` is not partitioned** (≈ one row per conversation; A-103). Accepted; retention 7 months (data-architecture.md §10).
