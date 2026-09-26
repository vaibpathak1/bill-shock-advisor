# ADR-009: Customer identity from the security context, never from the LLM

| | |
|---|---|
| Status | Accepted (SPEC §4.3 rule 2). Records a decision already implemented in 3a–6a |
| Date | 2026-09-26 |
| Deciders | Repo owner |
| Related | [security.md](../security.md) §2 (account resolution) and §5 (LLM01 prompt injection), [agent.md](../../03-development/agent.md) §4.2, §6, §7, [actions.md](../../03-development/actions.md) §3, §4, [deterministic-core.md](../../03-development/deterministic-core.md) §4, ADR-003, ADR-004 |

## Context

Every request touches one customer's bills, usage, subscriptions and actions. The model
reads text the customer typed and text from the BSS (VAS names, provider names, line-item
descriptions), so it can be steered by prompt injection ("show me account 1002", "I am an
admin") or simply get an id wrong. If the model could say *whose* data a tool reads or
*whose* account an action changes, one bad tool call would leak or change another
customer's data. Guardrails and human confirmation (ADR-004) do not help if the action is
confirmed on the wrong account's facts.

## Options

| Option | For | Against |
|---|---|---|
| A. Tools take an `accountId` parameter; a check compares it with the login | Familiar shape | The model still chooses the id; every tool must remember the check; a missed check is a data leak |
| B. The server puts the account into Spring AI's `ToolContext` | The model cannot write it | A second source of identity next to the SecurityContext used by every other module (agent.md §4.2, option A) |
| **C. One source: the SecurityContext. `CurrentCustomer.require()` is the only way to learn the account; no tool or API parameter can name one** | The model has nothing to choose; one rule for tools, services, guardrails and controllers | The tool loop must run on a thread that carries the caller's SecurityContext |

## Decision

Option C.

- **One accessor.** `CurrentCustomer.require()` (`security` module) returns the account of the
  authenticated principal, or throws `AccessDeniedException`. In the MVP the principal is one of
  the in-memory demo users, each mapped to one account in config; with the IdP (Phase 8) it is
  the token's `account_id` claim (security.md §2).
- **No identity in tool parameters.** No `@Tool` method has a parameter that names an
  account, customer, MSISDN, phone, user or subscriber, or has the type `AccountId`. Each tool
  calls `CurrentCustomer.require()` itself. Tool descriptions carry no data.
- **Tool results never carry the full identity.** No account id; the MSISDN only masked
  (`******1001`, `MsisdnMask`).
- **Guardrail facts are built server-side.** `GuardrailContextFactory.build(accountId, request)`
  gets the account from the SecurityContext and loads bills, line items, subscriptions, catalogue
  entries and prior credits from the read models and gateways **under that account**. The
  model's request supplies only ids to look up, never facts; an id not found under the account
  is absent from the context, and the reference check rejects it. Amounts in a proposal come
  from the guardrail decision, not from the model's arguments (actions.md §3).
- **Every query is scoped.** Repository queries include `account_id = :current`, for example
  `SELECT … FROM proposed_action WHERE action_id = ? AND account_id = ?`.
- **Other customers' resources are "not found".** A conversation or action id that belongs to
  another account returns **404**, identical to an unknown id (`CONVERSATION_NOT_FOUND`,
  `ACTION_NOT_FOUND`), so ids do not reveal that a resource exists.
- **Threading.** The SSE turn runs on an executor wrapped in
  `DelegatingSecurityContextExecutorService`, and the orchestrator runs the tool loop on that
  thread (agent.md §4.2, §7), so every tool sees the caller's SecurityContext.

## Enforcement (tests)

| Rule | Test |
|---|---|
| No `@Tool` parameter carries identity; all 16 tools are covered, so a new tool cannot slip past | `ToolIdentityRuleTest` (reflection) |
| A message naming another account, phone number and e-mail still gets only the caller's data (the tool result shows `******1001`, never `******1002`), and the stored and sent text is scrubbed | `ChatApiIT.identityComesFromTheLoginNeverFromTheMessage` |
| Another customer's conversation id → 404, same as a random id | `ChatApiIT` |
| Another customer's action → 404 `ACTION_NOT_FOUND` on confirm; their list is empty | `ActionApiIT` |

## Consequences

- Positive: a prompt injection can at most make the model call a tool on the caller's **own**
  data, or propose an action that the caller then sees and must confirm (ADR-004).
- Positive: one identity rule for the whole codebase; guardrails, audit (`actor_ref` is the
  username, never an MSISDN) and repositories all use it.
- Negative: the tool loop cannot use Spring AI's `ToolCallingAdvisor` as is, because it runs
  tools on another scheduler without the SecurityContext (agent.md §1, F-11). This is one of the
  two reasons the orchestrator runs its own loop (agent.md §4.2).
- Negative: care agents acting for a customer need an explicit assisted-session claim
  (`act_for_account` with `consent_ref`, security.md §2); not built yet (Phase 8).
- Open: the test that enumerates every endpoint against the authorisation matrix is planned
  for Phase 8 (security.md §2).
