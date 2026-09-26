# Actions (Phase 6a)

**Status: design approved (owner, 2026-09-26) with the review changes in §12; code in progress.** Scope: plan-and-budget §2a, row
"6a Actions" and "Demo wrap-up". Decisions this note builds on: ADR-004 (HITL, autonomy
ladder), SPEC §4.3 rules 3–5, §4.5, §4.7, deterministic-core.md §4 (guardrail chain), agent.md
§8.1 (action-claim gate), and the owner's 6a answers of 2026-09-26 (PROGRESS.md decisions log).

Out of scope (6b or later): Level 2 auto-approval, supervisor queue and roles, audit API,
runtime kill switch (`feature_flag`), outbox events, the diagnosis endpoint, the EXECUTING
reconcile **job** (§6.4), the expiry sweep job.

---

## 1. What already exists (3a–5a)

| Piece | Where | State |
|---|---|---|
| `proposed_action`, `idempotency_record`, `audit_events` (append-only, partitioned), `feature_flag` | V5 | Tables and indexes exist; nothing writes them yet |
| Guardrail chain for all 7 request types, `MoneyOutCap` | `actions`, `actions.guardrail` | Built and tested (4a) |
| Mock TMF678 / TMF622 / TMF621 gateways, idempotent per key | `bss.internal.mock` | Built (3a); no definite-rejection vs unknown distinction yet |
| `ToolCallAuditor` hook, called for every tool call | `audit` | No-op implementation |
| Tool loop, `TurnToolBudget`, sentence gate with action-claim rule | `agent` | Built (5a) |

---

## 2. State machine

6a implements every transition below except those marked **6b**. Changes from ADR-004
(owner answers, 2026-09-26): `FAILED` only on a **definite** BSS rejection; an unknown
outcome keeps `EXECUTING`; an `ESCALATED` action opens its TMF621 ticket at once.

```mermaid
stateDiagram-v2
    [*] --> PENDING_CONFIRMATION: action tool, guardrail PROPOSE
    [*] --> ESCALATED: guardrail ESCALATE, escalateToHuman, or tool budget spent
    PENDING_CONFIRMATION --> APPROVED: customer confirms, re-check passes, no supervisor flag
    PENDING_CONFIRMATION --> AWAITING_SUPERVISOR: customer confirms, supervisor flag set
    PENDING_CONFIRMATION --> REJECTED: customer rejects
    PENDING_CONFIRMATION --> REJECTED: confirm-time re-check rejects or the amount changed (SYSTEM)
    PENDING_CONFIRMATION --> ESCALATED: confirm-time re-check escalates (SYSTEM)
    PENDING_CONFIRMATION --> EXPIRED: TTL passed (checked lazily, SYSTEM)
    PENDING_CONFIRMATION --> AUTO_APPROVED: Level 2 policy (6b)
    AWAITING_SUPERVISOR --> APPROVED: supervisor approves (6b)
    AWAITING_SUPERVISOR --> REJECTED: supervisor rejects (6b)
    APPROVED --> EXECUTING: same transaction as APPROVED in 6a
    AUTO_APPROVED --> EXECUTING: (6b)
    EXECUTING --> EXECUTED: BSS confirms every step
    EXECUTING --> FAILED: BSS definitely rejects a step
    EXECUTING --> EXECUTING: timeout / connection error / unknown result; re-drive later
    EXECUTED --> [*]
```

- **Open states** (a proposal is "live"): `PENDING_CONFIRMATION`, `AWAITING_SUPERVISOR`,
  `APPROVED`, `AUTO_APPROVED`, `EXECUTING`, `ESCALATED`.
- **Terminal states:** `EXECUTED`, `FAILED`, `REJECTED`, `EXPIRED`.
- `EXECUTING` is terminal for the customer in 6a: only the re-drive (§6.3) moves it on.
- `ESCALATED` stays `ESCALATED`; the human handling the ticket works outside this system.
  The ticket id goes into `external_ref`.
- Customers can reject only in `PENDING_CONFIRMATION` (A-113).

### 2.1 Autonomy (fixed at Level 1)

`billshock.guardrails.autonomy-level` (existing key), read at startup (A-109):

| Level | Tools registered | Behaviour |
|---|---|---|
| 0 | read-only tools + `escalateToHuman` | Explanations only; `AutonomyCheck` also rejects other actions (defence in depth) |
| **1 (6a)** | all action tools | Every action needs customer confirmation |
| 2 | — | 6b; `autoApprove` is always `false` below Level 2 |

The runtime flag (`feature_flag`, kill switch, audited change) is 6b.

---

## 3. Action tools (`tools/ActionTools`)

Each tool: account from `CurrentCustomer.require()` (never an argument), conversation id from
the turn, then `ProposedActionService.propose(...)`. No tool executes anything. Descriptions
contain no thresholds, amounts or data.

| Tool | Parameters (LLM choices only) | `target_ref` (§5.3) |
|---|---|---|
| `proposeGoodwillCredit` | `amountExclGst` (string, copied), `reason`, `lineItemIds[]` | `goodwill:{billPeriod}` |
| `proposeVasUnsubscribe` | `subscriptionId`, `requestRefund` | `vas:{subscriptionId}` |
| `proposeThirdPartyBarring` | — | `barring` |
| `proposePlanChange` | `planCode`, `effective` (IMMEDIATE, NEXT_CYCLE) | `plan-change` |
| `proposeAddOn` | `addOnCode` | `add-on:{code}` |
| `raiseDispute` | `lineItemIds[]`, `reason` | `dispute:{sorted lineItemIds}` |
| `escalateToHuman` | `summary`, `reason` | `escalation:{conversationId}` |

`billPeriod`, amounts and line items in `target_ref` come from the guardrail decision (server
facts), never from the arguments.

### 3.1 Goodwill amount must be grounded (A-110)

The LLM never calculates amounts (ADR-003), yet `proposeGoodwillCredit` takes an amount. The
orchestrator therefore checks the argument **before** invoking the tool: `amountExclGst` must
equal the value of an `excl. GST` amount token among this turn's allowed amounts (the same set
the sentence gate uses, agent.md §8.1). Otherwise the tool is not called, the model gets
`{"status":"REFUSED","message":"Copy the amount before GST exactly from a tool result."}`, and
the call is audited with the new outcome `REFUSED_UNGROUNDED_ARGUMENT`. It counts toward the
turn budget.

**Scenario 1 needs one tool change for this:** `simulatePlans` returns savings only incl. GST
today (₹1,033.68), so the before-GST figure ₹876.00 appears in no tool result. Each plan and
add-on option gets a third amount, `savingExclGst` (`Amount.exclGst`), next to `newBill` and
`saving`. The system prompt (`system.v1.st`, edited in place; not deployed) gets one rule: a
goodwill credit for a missed pack uses that option's `savingExclGst` and the line items of the
matching cause. The display rule (4a gate) is unchanged: customers see `newBill` and `saving`.

*Alternative considered:* a basis-typed tool (`proposeGoodwillCredit(basis=MISSED_ADD_ON,
addOnCode)`) that computes the amount server-side. Stricter, but it changes the SPEC tool
signature; kept as an option for the owner (§11, Q-6a-1).

### 3.2 What the model gets back

```json
{"status":"PROPOSED","actionId":42,"type":"GOODWILL_CREDIT",
 "amount":{"value":"1033.68","display":"₹1,033.68 incl. GST"},
 "needsCustomerConfirmation":true,"needsSupervisorReview":true,
 "message":"Proposed. Nothing has changed yet. The customer confirms with the Confirm button; a supervisor then reviews it before it is applied."}
```

| Outcome | `status` | Message (fixed text, no reason codes or thresholds) |
|---|---|---|
| PROPOSE | `PROPOSED` | "Proposed. Nothing has changed yet. The customer confirms with the Confirm button." (+ supervisor sentence when flagged) |
| Existing open proposal (§5.3) | `ALREADY_PROPOSED` | Same as above, for the existing `actionId`; or "already done" when the existing one is `EXECUTED` |
| ESCALATE | `ESCALATED` | "Handed to a human colleague with reference {ticketId}." or, if the ticket could not be opened yet, "A human colleague will follow up." (§6.5) |
| REJECT | `NOT_POSSIBLE` | One neutral sentence per reason group: unknown id → "look it up with a tool first"; `USE_DISPUTE` → "this looks like a billing error; use raiseDispute"; `REFUND_NOT_ELIGIBLE_OPT_IN_PRESENT` → "complete opt-in record; unsubscribe without refund is possible"; amount reasons → "this amount cannot be proposed"; `ALREADY_ON_PLAN` → "already on this plan" |

Amounts are `Amount` display strings, so the sentence gate accepts them when the model repeats
them.

### 3.3 SSE `action` event

After a tool round that created or found a proposal, the orchestrator emits:

```
event: action
data: {"actionId":42,"type":"GOODWILL_CREDIT","status":"PENDING_CONFIRMATION",
       "summary":"Goodwill credit on the roaming charges of your September 2026 bill",
       "amount":"₹1,033.68 incl. GST","needsSupervisorReview":true,"expiresAt":"2026-09-27T10:15:00+05:30"}
```

`summary` is built server-side from a template per type, never from LLM text. The chat page
shows Confirm/Reject only for `PENDING_CONFIRMATION`.

### 3.4 Budget exhausted → deterministic escalation

SPEC §4.5 "max 8 tool calls per turn, then escalate". Today the 9th counted call ends in the
template. In 6a the orchestrator also creates the escalation itself (it does not rely on the
model): `escalateToHuman` semantics, masked summary built from the template facts (bill period,
verdict, total excess), reason `TOOL_BUDGET_EXHAUSTED`. Same `target_ref`, so it is created at
most once per conversation.

### 3.5 Action-claim gate and later turns

- The next turn gets a deterministic SYSTEM_CONTEXT line per action of the conversation, e.g.
  `Action 42 GOODWILL_CREDIT ₹1,033.68 incl. GST: AWAITING_SUPERVISOR`. The amounts join the
  allowed set.
- The action-claim gate allows a "done" claim only per **effect** with a `DONE` execution step in
  this conversation (§12, owner review): the receipts, not the status, decide.
- The digest and the auto-escalation are reached through a `tools` API
  (`ConversationActions`), so `agent` gains no dependency on `actions` (architecture.md §4 is
  unchanged).

---

## 4. REST API

All endpoints: role `CUSTOMER`, own account only, RFC 7807 errors with `code`.

| Endpoint | Notes |
|---|---|
| `GET /api/v1/actions?status=&page=&size=` | Newest first; `size` default 20, max 100; `{items, page, size, totalElements}`. Expired proposals are marked `EXPIRED` first (§6.6) |
| `POST /api/v1/actions/{id}/confirm` | Requires `Idempotency-Key`; §6 |
| `POST /api/v1/actions/{id}/reject` | Requires `Idempotency-Key`; optional body `{reason}` (scrubbed, 200 chars) |

| Case | Status | `code` |
|---|---|---|
| Header missing or not 1–100 chars `[A-Za-z0-9_-]` | 400 | `IDEMPOTENCY_KEY_REQUIRED` |
| Unknown id, or another account's action | 404 | `ACTION_NOT_FOUND` |
| Same key, same request | stored status and body | (replay) |
| Same key, different request | 422 | `IDEMPOTENCY_KEY_REUSED` |
| Same key, first request still running | 409 | `REQUEST_IN_PROGRESS` |
| Not `PENDING_CONFIRMATION`, or lost the version race | 409 | `ACTION_NOT_PENDING` |
| TTL passed | 409 | `ACTION_EXPIRED` |
| Re-check rejects / amount changed | 409 | `ACTION_NO_LONGER_VALID` |
| Confirmed, executed | 200 | body: action, `status: EXECUTED` |
| Confirmed, supervisor flag | 200 | `status: AWAITING_SUPERVISOR` |
| Confirmed, re-check escalates | 200 | `status: ESCALATED` |
| Confirmed, BSS outcome unknown | **202** | `status: EXECUTING`, "We are checking with the billing system" |
| Confirmed, BSS definitely rejected | 200 | `status: FAILED` (the confirm itself succeeded; the action failed) |

---

## 5. Data changes (V5, edited in place, owner OK 2026-09-26)

Anyone with a local database runs `docker compose down -v` afterwards.

```sql
-- two new columns inside CREATE TABLE proposed_action (no ALTER; V5 is edited in place)
    target_ref          text          NOT NULL,
    execution           jsonb         NOT NULL DEFAULT '[]'::jsonb,  -- one entry per BSS step (§6.2)

-- §5.3: at most one live action per target
CREATE UNIQUE INDEX proposed_action_target_uq ON proposed_action (account_id, action_type, target_ref)
    WHERE status IN ('PENDING_CONFIRMATION','AWAITING_SUPERVISOR','APPROVED','AUTO_APPROVED','EXECUTING','ESCALATED')
       OR (status = 'EXECUTED' AND action_type IN ('GOODWILL_CREDIT','VAS_UNSUBSCRIBE','THIRD_PARTY_BARRING','DISPUTE'));

-- X2 widened: a credit whose outcome is unknown may have been applied
CREATE INDEX proposed_action_credit_history_idx ON proposed_action (account_id, decided_at)
    WHERE action_type = 'GOODWILL_CREDIT' AND status IN ('EXECUTING','EXECUTED');
```

- `execution` is a second column beyond the `target_ref` the owner approved; flagged in §11
  (Q-6a-2). The alternative is only `external_ref` plus audit rows.
- `idempotency_record` is unchanged. `proposed_action.idempotency_key` (UNIQUE) stores the BSS
  key `pa-{actionId}`.

### 5.1 Row contents

- `amount` / `gst_amount`: from the guardrail decision (before GST / GST, Q-20). For a dispute,
  the disputed amount; for a plan change or add-on, `NULL`.
- `params`: the request as chosen by the LLM (ids, codes, effective date), with free text
  scrubbed (`PiiScrubber`) and cut to 200 characters.
- `guardrail_result`: outcome, reason codes, `supervisorRequired`, `refundAmountIncomplete`.
  Server-side only; never returned to the model.
- `expires_at`: `created_at + billshock.actions.pending-ttl` (default `PT24H`, A-111).
- `decided_by`: principal name for the customer, `system` for re-check/expiry.

### 5.2 Prior credit

`GuardrailContextFactory.hasPriorCredit` also counts `GOODWILL_CREDIT` actions in `EXECUTING`
or `EXECUTED` with `decided_at` in the look-back window (today it reads only bill line items).
At confirm time the action being confirmed is excluded.

### 5.3 Dedupe by `target_ref`

`propose` inserts with `ON CONFLICT DO NOTHING` on `proposed_action_target_uq`; on conflict it
returns the existing row (`ALREADY_PROPOSED`). Consequences:
- Two proposals of the same VAS refund can never both be confirmed (no double refund, even
  though each would get its own BSS key).
- One goodwill credit per bill while one is live or executed; one ticket per conversation for
  escalations (owner answer 2).
- Plan change and add-on are deduped only while live (a later plan change is legitimate).
- `REJECTED`, `EXPIRED` and `FAILED` free the target, so the model may propose again.
- A plan-change proposal to a different plan while one is pending returns the pending one; the
  customer rejects it first (A-114).

---

## 6. Confirm flow

### 6.1 Sequence

```mermaid
sequenceDiagram
    autonumber
    participant C as Chat page
    participant API as ActionController
    participant S as ProposedActionService
    participant G as GuardrailService
    participant DB as PostgreSQL
    participant X as Executor (Strategy)
    participant B as Mock BSS

    C->>API: POST /actions/42/confirm, Idempotency-Key k
    API->>S: confirm(account, 42, k, requestHash)
    Note over S,DB: Transaction T1
    S->>DB: INSERT idempotency_record (account, k, hash, 202, {IN_PROGRESS})
    alt key exists
        DB-->>S: conflict (after the other T1 commits)
        S-->>API: same hash: stored response, or 409 REQUEST_IN_PROGRESS; else 422
    end
    S->>DB: SELECT action (account, 42)
    alt expired
        S->>DB: status EXPIRED, audit; record 409 ACTION_EXPIRED
    end
    S->>G: evaluate(account, request)  (re-check, fresh facts)
    alt REJECT or amount changed
        S->>DB: status REJECTED (system), audit; record 409 ACTION_NO_LONGER_VALID
    else supervisor flag
        S->>DB: status AWAITING_SUPERVISOR, audit; record 200
    else ESCALATE
        S->>DB: status ESCALATED, audit; ticket after commit (§6.5)
    else PROPOSE
        S->>DB: UPDATE status EXECUTING, decided_by/at WHERE status = PENDING AND version = v
        Note right of DB: 0 rows → rollback → 409 ACTION_NOT_PENDING
        S->>DB: audit ACTION_CONFIRMED, ACTION_EXECUTING
    end
    Note over S,DB: commit T1 (no BSS call inside a transaction)
    S->>X: execute(action) with key pa-42
    X->>B: step 1 … step n (same key)
    alt every step confirmed
        B-->>X: receipts
        Note over S,DB: T2: status EXECUTED, execution, external_ref, audit; record 200
    else a step definitely rejected
        B-->>X: BssRejectedException
        Note over S,DB: T2: status FAILED, failure_reason, audit; record 200 FAILED
    else timeout / connection error / unknown
        B-->>X: BssUnavailableException
        Note over S,DB: T2: stays EXECUTING, audit ACTION_OUTCOME_UNKNOWN; record 202 EXECUTING
    end
    API-->>C: response stored in idempotency_record
```

`APPROVED` is written and left in the same statement sequence of T1 (both audited), because in
6a nothing sits between approval and execution. It becomes a resting state with the supervisor
flow in 6b.

### 6.2 Executors (Strategy)

| Type | BSS steps, in order, all with key `pa-{actionId}` |
|---|---|
| `GOODWILL_CREDIT` | TMF678 `requestAdjustment(GOODWILL_CREDIT, amount, GST)` |
| `VAS_UNSUBSCRIBE` | TMF622 `VAS_UNSUBSCRIBE`; then, if a refund was proposed, TMF678 `REFUND` |
| `THIRD_PARTY_BARRING` | TMF622 `THIRD_PARTY_BARRING` |
| `PLAN_CHANGE` | TMF622 `PLAN_CHANGE` (effective date in the order) |
| `ADD_ON` | TMF622 `ADD_ON` |
| `DISPUTE` | TMF621 `BILLING_DISPUTE` with the line-item ids |
| `ESCALATION` | TMF621 `ESCALATION` (§6.5) |

- One key per action is enough: each gateway deduplicates on its own, and an action calls each
  gateway at most once.
- `execution` records each step as `{step, status: DONE|REJECTED|UNKNOWN, ref, at}`. A step
  already `DONE` is still re-sent on a re-drive (the BSS answers from its key), so no local
  state is trusted over the BSS.
- **Unsubscribe first, refund second:** stopping future charges matters most. If the refund is
  definitely rejected after the unsubscribe succeeded, the action is `FAILED` with
  `failure_reason = REFUND_REJECTED_AFTER_UNSUBSCRIBE`; the done step stays recorded. The
  runbook (Phase 12) covers the manual refund.

### 6.3 Unknown outcome and re-drive (owner change, 2026-09-26)

- New exception in `bss`: `BssRejectedException(code)` = the BSS answered and **definitely did
  not apply** the request (a 4xx business rejection). `BssUnavailableException` (existing) =
  timeout, connection error, 5xx, open circuit: the outcome is **unknown**, the effect may have
  been applied.
- Only `BssRejectedException` moves an action to `FAILED`. Anything else (including any other
  runtime exception from an adapter) leaves it `EXECUTING`, audited as
  `ACTION_OUTCOME_UNKNOWN`.
- **Re-drive** (`ActionExecution.redrive(actionId)`, public in `actions`, used by tests in 6a
  and by the 6b job): only for `EXECUTING`; claims the row with `UPDATE … SET version =
  version + 1 WHERE status = 'EXECUTING' AND version = v` so two re-drives cannot run together;
  runs the same steps with the same key; ends in `EXECUTED`, `FAILED` or stays `EXECUTING`.
  The idempotency record of the original confirm is **not** rewritten; clients read the
  current state through `GET /actions` (A-112).
- Customers cannot re-drive: a second confirm on an `EXECUTING` action is `409
  ACTION_NOT_PENDING` (or the stored 202 when the same key is replayed).

### 6.4 EXECUTING reconcile job (6b, described here)

- Scheduled (ShedLock in Phase 7 when there are several pods): selects `EXECUTING` actions whose
  last attempt is older than `billshock.actions.reconcile.min-age` (default 5 min), oldest
  first, a bounded batch.
- For each: `redrive`. Backoff between attempts (5 min, 15 min, 1 h, then hourly); the attempt
  count lives in `execution`.
- After `max-attempts` (default 6) it stays `EXECUTING`, raises the alert
  `actions_stuck_executing` and appears in the supervisor queue. It is **never** moved to
  `FAILED` automatically, because the effect may exist.
- Also picks up `ESCALATED` actions without a ticket reference (§6.5).
- Metrics: `actions_redrive_total{result}`, gauge `actions_executing_age_seconds_max`.

### 6.5 Escalations (owner answer 2)

`ESCALATED` rows (from `escalateToHuman`, the budget rule, or a guardrail `ESCALATE` such as a
credit above the money-out cap) open the TMF621 ticket **immediately, without customer
confirmation**: T1 inserts the row and audit, commit, then the ticket call with `pa-{id}`,
then T2 writes `external_ref`. Dedupe: `escalation:{conversationId}` for hand-offs; an escalated
goodwill credit keeps `goodwill:{billPeriod}`. If the ticket call fails (either kind), the row
stays `ESCALATED` with no `external_ref`, the model is told "a colleague will follow up", and
the 6b job opens it (same key, so no duplicate ticket).

### 6.6 Idempotency and concurrency rules

1. **Scope:** a key is scoped to the account (`idempotency_record` PK). The request hash is
   SHA-256 over `METHOD`, the path (so the same key on another action or on `/reject` is a
   different request) and the canonical JSON body.
2. **Claim first:** T1 inserts the record before anything else. A concurrent request with the
   same key blocks on the primary key until T1 ends, then sees the row: same hash → the stored
   response, or `409 REQUEST_IN_PROGRESS` while the body still says `IN_PROGRESS`; different hash
   → `422`.
3. **Final response:** T2 (or T1 for outcomes without execution) overwrites the record with the
   final status and body. A replay returns exactly that. Error outcomes (`409 ACTION_EXPIRED`
   etc.) are stored too, so a replay is stable.
4. **State transitions** use `WHERE status = :expected AND version = :v`, `version + 1`. Zero
   rows → rollback (the idempotency claim is rolled back too) → `409 ACTION_NOT_PENDING`. So two
   confirms with **different** keys cannot both reach `EXECUTING`.
5. **BSS:** every step carries `pa-{actionId}`; the mocks already return the first receipt for a
   repeated key. Double-crediting would need both the state machine and the BSS key to fail.
6. **Expiry** is checked in T1 of confirm/reject and on `GET /actions` (an UPDATE to `EXPIRED`
   with audit rows, same transaction as the read). No sweep job in 6a.
7. **Keys are kept** indefinitely in 6a (retention with the 3b job, A-115).

---

## 7. Audit (`audit` module)

`JdbcAuditLog` replaces `NoOpToolCallAuditor` and also serves the `actions` module. Every row:
`occurred_at`, `account_id`, `conversation_id`, `correlation_id` (MDC), `actor_type`,
`actor_ref`, `event_type`, `tool_name`/`action_id` where relevant, `prompt_version`, masked
`payload`, `result_hash`.

| Event | Actor | Written |
|---|---|---|
| `TOOL_CALLED` (outcome: EXECUTED, REFUSED_ALREADY_CALLED, REFUSED_BUDGET, REFUSED_UNGROUNDED_ARGUMENT) | AGENT | Own transaction per call; a failed write ends the turn in the template (fail closed) |
| `ACTION_PROPOSED`, `ACTION_ALREADY_PROPOSED`, `ACTION_NOT_POSSIBLE` (guardrail REJECT, no row) | AGENT | With the insert |
| `ACTION_ESCALATED`, `TICKET_OPENED`, `TICKET_NOT_OPENED` | AGENT / SYSTEM | With the state change |
| `ACTION_CONFIRMED`, `ACTION_REJECTED` | CUSTOMER | T1 |
| `GUARDRAIL_RECHECKED` (outcome, reasons) | SYSTEM | T1 |
| `ACTION_AWAITING_SUPERVISOR`, `ACTION_EXPIRED`, `ACTION_EXECUTING` | SYSTEM | Same transaction |
| `ACTION_EXECUTED`, `ACTION_FAILED`, `ACTION_OUTCOME_UNKNOWN`, `ACTION_REDRIVEN` | SYSTEM | T2 |

- Payloads: tool arguments and free text through `PiiScrubber`; amounts as `Money` values;
  reason codes (server-side only, never shown to the model).
- `actor_ref`: the principal's username (`cust1001`), never an MSISDN (A-116).
- The V5 trigger already rejects UPDATE and DELETE.

---

## 8. Demo wrap-up

### 8.1 Chat page (`src/main/resources/static/`)

- `index.html`, `app.js`, `app.css`; no framework, no external scripts.
- Login form (username/password); the credentials stay in a JS variable only (A-117).
- `fetch` POST to `/api/v1/chat`, reading SSE from the response stream (`EventSource` cannot
  POST). Renders `summary`, text, `reset`, `fallback`, `action` events.
- Action cards: Confirm/Reject with a key from `crypto.randomUUID()`, generated once per card and
  button and **reused on retry**, so a double click or a retry is a replay. A 202 shows "checking
  with the billing system". A "My actions" list calls `GET /api/v1/actions`.
- All server text is inserted with `textContent` (untrusted descriptions, security.md §5).
- Security: `/`, `/index.html`, `/app.js`, `/app.css` are `permitAll`; header
  `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self';
  frame-ancestors 'none'`.

### 8.2 Scripted demo (owner answer 3: option c)

- Runs with `./mvnw spring-boot:test-run` (the `test-run` goal of `spring-boot-maven-plugin`;
  verified 2026-09-26 in the pinned 4.1.1 plugin descriptor: runs on the test runtime classpath; the main class is chosen with `-Dspring-boot.run.main-class`). A `DemoApplication` main in
  `src/test/java` starts the real app plus `ScriptedDemoConfiguration`, which points
  `billshock.llm.chat.base-url` at the fake Anthropic API. **Nothing scripted ships in the jar.**
- The real `AnthropicChatModel`, tools, gates, SSE, guardrails, executors and audit all run;
  only the model's words and tool choices are scripted.
- Script selection: the fake API is a FIFO queue today. The demo mode adds a **scenario
  selector**: the scenario is identified from the pre-fetch context in the request (each seed
  account's current bill total is unique), and the round is the number of tool results in the
  request. Unknown input → a canned "the scripted demo only covers the six seed scenarios" text.
- Banner on the page, fixed at the top, when the server reports demo mode (a `demoMode` field in
  a small `GET /api/v1/meta` response): **"Scripted model — not a live LLM."** The README uses
  the same words.

### 8.3 Scenario outcomes (README demo script and E2E tests)

| # | Account | Expected proposals | Demo path |
|---|---|---|---|
| 1 | 1001 | `IR_GCC_7D` recommended for future trips (no add-on proposal); goodwill credit ₹876.00 before GST = **₹1,033.68 incl. GST** on the 3 roaming line items, described as retroactively applying the pack (pay-per-use charges minus the pack price) | Confirm → **`AWAITING_SUPERVISOR`** (above the share and ₹ limits; 6b supervisor) |
| 2 | 1002 | Plan change to `PP_499`, `NEXT_CYCLE` (A-118) | Confirm → `EXECUTED` (TMF622 order) |
| 3 | 1003 | Unsubscribe Astro Daily with refund **₹231.28 incl. GST** (4 line items); third-party barring; no refund for Cricket Scores | Confirm refund → **`EXECUTED`**: unsubscribe order + refund adjustment, each once; replay → same response |
| 4 | 1004 | None (proration explained) | No `proposed_action` rows |
| 5 | 1005 | `raiseDispute` on the duplicate line item, **₹706.82 incl. GST**; no goodwill | Confirm → `EXECUTED` (TMF621 ticket) |
| 6 | 1006 | None ("your bill is normal") | No `proposed_action` rows |

1001 check: 876.00 × 9% = 78.84 CGST + 78.84 SGST → ₹1,033.68; 15% of ₹2,919.32 = ₹437.90;
₹1,033.68 > ₹437.90 and > ₹500, ≤ ₹2,000 → supervisor, not escalated.

---

## 9. Tests

Unit:
- `ProposedActionService`: mapping of every guardrail outcome; dedupe returns the existing row;
  re-check paths (reject, amount changed, supervisor, escalate).
- Executors: step order; a definite rejection → `FAILED`; unknown → `EXECUTING`; refund rejected
  after unsubscribe → `FAILED` with the done step recorded.
- Request hashing; key validation; action-claim gate allowance; grounded-argument check
  (₹876.00 accepted only after `simulatePlans`; a typed figure refused).

Integration (Testcontainers, `FakeAnthropicApi`):
- `ActionApiIT`: every row of the §4 table, incl. another account's id → 404.
- **Concurrency:** two confirms with different keys started together → exactly one 200, one 409,
  one BSS effect. Two with the same key → one executes, the other gets the stored response or
  `REQUEST_IN_PROGRESS`, one effect.
- **Unknown outcome (owner change):** a test-only fault-injecting gateway wrapper that
  (a) applies the effect and then throws a timeout ("response lost"), and (b) throws before
  applying. Both → response 202, status stays `EXECUTING`, audit `ACTION_OUTCOME_UNKNOWN`.
  Then `redrive` → `EXECUTED` and the mock shows **exactly one** effect for `pa-{id}` in both
  cases. A definite rejection → `FAILED`, and a re-drive of a `FAILED` action is refused.
- Two concurrent `redrive` calls → one BSS call sequence.
- Expiry (clock bean moved forward) → `409 ACTION_EXPIRED`, row `EXPIRED`, audited.
- Escalation: ticket opened at once; a second `escalateToHuman` in the conversation → same row,
  one ticket; ticket failure → `ESCALATED` without `external_ref`, re-drive opens it once.
- Audit: each flow leaves the expected event sequence; UPDATE/DELETE still rejected.
- Level 0: only the read-only tools and `escalateToHuman` are registered (`ChatToolsRegistrationTest`,
  a unit test, so the shared IT context keeps Level 1).
- **Scenario E2E** (`ScenarioE2EIT`, one per scenario, §8.3), over HTTP with the same scripts the
  scripted demo uses.
- Static page: served without auth, CSP header present.

`./mvnw verify` must pass; `ModularityTests` confirms no new module edges.

---

## 10. Files (as built)

- `bss`: `BssRejectedException`; mocks throw it for a definite rejection (unknown subscription,
  already unsubscribed).
- `bss`: `BssRejectedException`; `OrderRequest.effective`; the mock TMF622 rejects an unknown VAS
  unsubscribe.
- `actions` (public): `ProposedActionService`, `ActionExecution`, `ActionView`, `ActionPage`,
  `ProposalResult`, `CommandResponse`, `ActionType`, `ActionStatus`, `ActionEffect`,
  `ExecutionStep`, `IdempotencyKeys`, `ActionsProperties`; `guardrail.CreditHistory`.
- `actions.internal`: `ActionWorkflow` (implements both services), `ProposedActionRepository`
  (`JdbcClient`, A-119), `IdempotencyStore`, `ActionParams`, `ActionRow`, `ActionViews`,
  `TargetRefs`, `Problems`, `ExecutionRunner`, executors `GoodwillCreditExecutor`,
  `VasUnsubscribeExecutor`, `OrderExecutor`, `TicketExecutor`.
- `audit`: `AuditLog`, `AuditEvent`, `internal.JdbcAuditLog` (also the `ToolCallAuditor`);
  `NoOpToolCallAuditor` removed; `ToolCallAuditor` takes the account.
- `tools`: `ActionTools`, `EscalationTool`, `ActionToolResult`, `ConversationActions`;
  `CatalogTools` `savingExclGst`.
- `agent`: tool registration by autonomy level, `TurnToolBudget.ArgumentCheck` (A-110), SSE
  `action` event, budget escalation, digest, per-effect claim gate, prompt rules, progress wording.
- `api`: `ActionController`, `MetaController`, `MetaProperties`, `ApiRequestException`;
  `security`: public static page and meta, CSP, `CurrentCustomer.username()`.
- V5 edited in place; `application.yml`: `billshock.actions.*`, `billshock.meta.*`.
- `src/main/resources/static/`: `index.html`, `app.js`, `app.css`.
- Tests: `ActionApiIT`, `ScenarioE2EIT`, `MetaAndPageIT`, `ExecutionRunnerTest`, `ActionViewsTest`,
  `ActionToolsTest`, `ChatToolsRegistrationTest`, additions to `GroundingGateTest`,
  `TurnToolBudgetTest`, `ToolIdentityRuleTest`, `ToolsSeedIT`, `ChatApiIT`, `MockGatewaysTest`.
  Support: `BssFaults` (fault injection and effect counts), `ScenarioScripts`, `ActionClient`,
  the `FakeAnthropicApi` selector; `demo.ScriptedDemoApplication`.

---

## 11. Choices to confirm at this gate

1. **Q-6a-1:** grounded `amountExclGst` + `savingExclGst` in `simulatePlans` (§3.1), or a
   basis-typed goodwill tool that computes the amount server-side (changes the SPEC signature)?
2. **Q-6a-2:** the extra `execution jsonb` column in V5 (§5) for per-step receipts, or only
   `external_ref` plus audit rows?
3. **Q-6a-3:** dedupe scope (§5.3): executed goodwill blocks another goodwill for the **same
   bill**; executed barring, VAS unsubscribe and disputes block repeats; plan change and add-on
   only while live. OK?
4. **Q-6a-4:** confirm-time amount change (§6.1): if the re-check computes a different amount
   than the customer saw (e.g. a new VAS charge arrived), the action is rejected (`409
   ACTION_NO_LONGER_VALID`) rather than executed with the new amount. OK?
5. **Q-6a-5:** budget exhaustion creates the escalation deterministically (§3.4). OK?
6. **Q-6a-6:** `GET /api/v1/meta` (demo-mode flag only) is a small endpoint not in SPEC §4.7,
   added for the banner. OK, or should the banner come from a static file generated only in the
   scripted run?

---

## 12. Design review answers (owner, 2026-09-26)

All six choices accepted, with these clarifications and additions:

1. **Q-6a-1:** grounded amount + `savingExclGst`.
2. **Q-6a-2:** `execution jsonb` in V5.
3. **Q-6a-3:** a dispute's `target_ref` is **its set of line items**, not the bill, so disputes
   on different charges of the same bill are not blocked. Because the unique index only
   catches identical sets, `propose` also refuses a dispute whose line items **overlap** a live
   or executed dispute (returns the existing one as `ALREADY_PROPOSED`).
4. **Q-6a-4:** 409 `ACTION_NO_LONGER_VALID` on a changed amount. The problem body carries
   `reason: AMOUNT_CHANGED`, the amount the customer saw and the new amount (display strings),
   and the text "The amount has changed since this was proposed. Nothing was changed on your
   account. Ask for a fresh proposal." The action's digest line in the next turn says
   `REJECTED (amount changed)`, and the prompt tells the model to explain this and offer a
   fresh proposal.
5. **Q-6a-5:** deterministic escalation on budget exhaustion.
6. **Q-6a-6:** `GET /api/v1/meta` returns **only** `{demoMode, banner, version}`: no thresholds,
   model names or other config. A test asserts the exact key set.

**Partial VAS execution (added):** when the unsubscribe succeeds and the refund is definitely
rejected, the action is `FAILED` with `execution` = `[{UNSUBSCRIBE, DONE}, {REFUND, REJECTED}]`.
- The confirm response, the SSE/digest line and the model's context say: the subscription is
  cancelled; the refund will be handled manually by the billing team.
- The action-claim gate reads the **execution receipts**, not only the status: a claim is
  allowed per effect (`UNSUBSCRIBE`, `REFUND`, `CREDIT`, `BARRING`, `PLAN_CHANGE`, `ADD_ON`,
  `DISPUTE`, `ESCALATION`) only when a step with that effect is `DONE`. Here the unsubscribe
  claim is allowed and any refund claim is blocked.
- Test: partial execution → `FAILED`, receipts as above; in the next turn "Astro Daily has been
  cancelled" passes the gate and "your refund has been processed" is rewritten.

---

## 13. Implementation notes and deviations (code gate)

1. **Confirm/reject bodies are built in `actions`,** not in the controller advice, because they are
   stored with the idempotency key and replayed as stored (§6.6). Same Problem Details shape
   (`type`, `title`, `status`, `detail`, `code`).
2. **`jsonb` normalises the stored body** (key order, spacing). The first response is therefore
   also returned from the stored text, so the first response and every replay are byte-identical;
   the key order differs from the other endpoints' JSON.
3. **`APPROVED` is not persisted as a separate row state in 6a:** one guarded update moves
   `PENDING_CONFIRMATION → EXECUTING`; `ACTION_APPROVED` and `ACTION_EXECUTING` are both audited.
   It becomes a resting state with the supervisor flow (6b).
4. **A crash between T1 and T2** leaves the action `EXECUTING` (safe; re-driven by the 6b job) and
   its idempotency record "in progress", so a replay of that key answers `409 REQUEST_IN_PROGRESS`
   until the 6b job also completes the record. Noted for the 6b job.
5. **Dispute overlap** (answer 3) is checked in the service before the insert. Identical line-item
   sets are also protected by the unique index; two *different but overlapping* sets proposed at
   the same instant could both be created. Low risk (one chat turn at a time, A-108); 6b can add a
   per-account lock if needed.
6. **Claim detection:** passive claims cover money out, unsubscribe, barring and the generic
   verbs processed/raised/filed/submitted. "changed", "activated", "applied" and "issued" are not
   passive claims, because bill explanations use them (scenario 4). A generic verb counts only
   with a known noun (refund, credit, dispute, ticket/specialist/colleague); without one it never
   passes. When some effect is done, the rewrite no longer says "I haven't changed anything".
7. **The mock TMF622 does not change its fixture data** after an order: an unsubscribed VAS still
   appears in `getActiveSubscriptions`. The dedupe index stops a second refund regardless.
8. **Customer reject reasons** are kept only in the audit payload (scrubbed), not on the row.
9. **Proposals made outside a chat** (tests only) have no conversation; their hand-off target is
   `escalation:null`, so they dedupe per account.
10. **Verified:** `spring-boot:test-run` in the pinned 4.1.1 plugin descriptor; `SpringApplication
    .from(…).withAdditionalProfiles(…)` in Boot 4.1.1. The scripted demo was run once against a
    throwaway PostgreSQL (not `.env`): meta banner, scenario 3 confirm and the turn-2 claim, and the
    chat page in a browser (no console or CSP errors).

