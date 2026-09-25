# Deterministic Core (Phase 4a)

| | |
|---|---|
| Status | **For review at the 4a gate** (design note and code reviewed together, Q-31) |
| Date | 2026-09-25 |
| Spec reference | SPEC.md §4.2, §4.3, §4.4, §4.5, §11 (4a); plan-and-budget.md §2a |
| Related | ADR-003 (LLM never does arithmetic), ADR-004 (ProposedAction, autonomy ladder), [seed-scenarios.md](seed-scenarios.md) §5.7, §7, [llm-architecture.md](../02-design/llm-architecture.md) §9, [assumptions.md](../01-requirements/assumptions.md) A-84 to A-95 |

This note defines the exact behaviour of the Phase 4a engines: `BillDiffEngine`,
`PlanSimulator`, the guardrail chain, the per-turn tool-call budget and the INR formatter.
Every amount is computed in Java with `BigDecimal` and rounded HALF_EVEN to 2 decimal
places (`Money`). No engine calls an LLM. The test values are the ones in
seed-scenarios.md.

---

## 1. Where the code lives

| Module | Types (public API) | Why there |
|---|---|---|
| `domain` | `InrFormat`, `Money.average` | Shared kernel; the formatter is needed by the tools (5a), templates (5a) and notifications (7) |
| `bss` | `CatalogReadModel`, `RoamingBandProperties`; `BillingReadModel.latestBill`, `.roamingUsage`; `UsagePeriodSummary.isdMin` | Read access to the local replicas (plan, tariff, add-on, roaming by country) |
| `analysis` | `BillDiffEngine`, `BillDiff`, `DuplicateChargeDetector`, `PlanSimulator`, `PlanSimulation`, `AnalysisProperties` | Deterministic engines (ADR-003) |
| `actions` | `GuardrailService`, `ActionRequest`, `GuardrailDecision`, `GuardrailProperties`; internal: `GuardrailContext`, `GuardrailContextFactory`, the checks | Guardrail chain (ADR-004); the `ProposedAction` workflow is added in 6a |
| `agent` | `ToolCallBudget`, `ToolCallBudgetProperties` | Pure counting policy; the Spring AI `ToolCallingManager` decorator that uses it (`TurnToolBudget`) is wired in 5a (Q-29) |

**Module map change:** `actions` now also depends on `analysis`, so the guardrails can
reuse `DuplicateChargeDetector` rather than a second copy of the rule. This adds no cycle,
because `analysis` depends only on `domain` and `bss`. architecture.md §4 is updated.

## 2. `BillDiffEngine`: `diff(account, billPeriod, baselineMonths = 3)`

### 2.1 Inputs

- **The current bill:** the bill of `billPeriod`. If there is no bill, the result is empty.
- **The baseline bills:** the account's bills in the `baselineMonths` billing months before
  `billPeriod`. With fewer bills (a new customer, or a gap), the bills that exist are used and
  `baselineBillCount` says how many. With none, the verdict is `INSUFFICIENT_HISTORY` (A-87).
- **Line items:** all non-TAX line items of those bills. Line items are authoritative for
  money; usage aggregates only explain quantities (data-architecture.md).

### 2.2 Cause groups

Line-item categories are mapped to cause groups. A plan change replaces RENTAL with
PRORATION lines, so both belong to `PLAN`. Otherwise account 1004 would show −₹399.00
rental and +₹544.17 proration, not the +₹145.17 in seed-scenarios.md §7.

| Group | Categories |
|---|---|
| `PLAN` | RENTAL, PRORATION |
| `DATA` | DATA |
| `VOICE` | VOICE (domestic and ISD) |
| `SMS` | SMS |
| `ROAMING` | ROAMING |
| `VAS` | VAS |
| `OTHER` | ADJUSTMENT, CREDIT, OTHER |

### 2.3 Arithmetic

With `n` = the number of baseline bills, and each mean computed as one division rounded
HALF_EVEN to 2 dp (`Money.average`):

1. Per group `g`: `current_g` = Σ current lines; `baseline_g` = mean over the baseline
   bills of Σ lines; `delta_g = current_g − baseline_g`. These are the **group deltas**,
   always returned (`groupDeltas`).
2. `baselineSubtotal` = mean of the baseline subtotals; `baselineTotal` = mean of the
   baseline totals (both GST-exclusive / GST-inclusive as billed).
3. `subtotalExcess = current.subtotal − baselineSubtotal`;
   **`totalExcess = current.total − baselineTotal`** (the authoritative figure; it matches
   seed-scenarios.md §5.7).
4. `excessPercent = totalExcess ÷ baselineTotal × 100`, 1 dp; `ratio = current.total ÷
   baselineTotal`, 3 dp (both reported only; decisions use the exact values).

### 2.4 Verdict: the chat rule (Q-22, A-83)

`MEANINGFUL_INCREASE` if `totalExcess ≥ ₹100.00` **and** `totalExcess × 100 ≥ 10 ×
baselineTotal` (config `billshock.analysis.chat.min-excess-inr`, `…min-excess-pct`). A
zero baseline total passes the percentage test. Otherwise `NORMAL`. This rule is separate
from the proactive `AnomalyDetector` rule (4b) and shares no config key with it.

### 2.5 Causes (only when the verdict is `MEANINGFUL_INCREASE`)

For a `NORMAL` bill the cause list is empty, so nothing can be presented as a cause
(NFR-08, US-DIA-02). The group deltas stay available for transparency.

1. Every group with `delta_g ≠ 0` is a cause, ranked by `delta_g` descending. Negative
   causes are offsets (for example a lower rental after a downgrade), so that the causes
   still add up to the total excess.
2. **The largest cause** is the first in the ranking.
3. `amountExclGst = delta_g`. Any difference `subtotalExcess − Σ delta_g` (the per-group
   means can round differently from the subtotal mean) is added to the largest cause.
4. `gstAmount` = the GST rule of the current bill's supply type applied to
   `amountExclGst` (CGST + SGST or IGST, each HALF_EVEN; `GstCalculator`).
5. `amountInclGst = amountExclGst + gstAmount`. Any difference `totalExcess − Σ
   amountInclGst` is added to the largest cause's `gstAmount` and `amountInclGst`.
6. So **Σ `amountInclGst` = `totalExcess` exactly** (US-DIA-01). Both reconciliation
   amounts are returned (`roundingAdjustmentExclGst`, `roundingAdjustmentGst`) so that an
   auditor can see them. For all 6 seed accounts both are ₹0.00.
7. Each cause lists the line-item ids of the current bill that make it up.

If the verdict is `MEANINGFUL_INCREASE` but no group changed, which is only possible
through rounding, the cause list is empty and the rounding amounts show why.

### 2.6 Findings (always evaluated, whatever the verdict)

A billing error is an issue even on a bill with a normal total, so findings do not depend
on the verdict:

| Finding | Rule | Seed |
|---|---|---|
| `DUPLICATE_CHARGE` | Two or more non-TAX lines on the current bill with the same category, amount, service period, subscription and a non-null `external_ref` (`DuplicateChargeDetector`). The first line (lowest id) is the original; the others are the duplicates | 1005: line 1005260902 duplicates 1005260901 |
| `NEW_SUBSCRIPTION_CHARGE` | A charge whose `subscription_id` appears on none of the baseline bills | 1003: `SUB-1003-VAS-ASTRO`, 4 lines |
| `PLAN_CHANGE_PRORATION` | PRORATION lines on the current bill | 1004: 2 lines |

The opt-in status of a new subscription is **not** part of the diff: it comes from BSS
through `getActiveSubscriptions` (5a) and the guardrail context (§4).

## 3. `PlanSimulator`: `simulate(account, billPeriod)`

### 3.1 Outcomes

| Status | When | Content |
|---|---|---|
| `SIMULATED` | Normal case | The re-rated current plan, up to 3 cheaper plans, the best single add-on (may be none) |
| `RECENT_PLAN_CHANGE` | The billed usage period contains a plan change (Q-27) | The change date, the old and new plan; **no ranking** |
| `NOT_SIMULATABLE` | No usage aggregate for the period; the current plan is not in the catalogue; a day-based tariff would be needed (ADR-007, the BSS-detail path is later); a roaming country without a configured band | A reason code |

If there is no bill for the period, the result is empty.

### 3.2 The plan-change rule (Q-27)

**Rationale:** a period with a mid-cycle plan change mixes usage across two plans, so it
is not representative for re-rating. A period is mixed when a `PLAN_CHANGE` order that is
`COMPLETED` has an effective date `d` with `periodStart < d ≤ periodEnd`. If BSS returned no
such order but the bill has PRORATION lines, the latest proration service-period start is
used as `d`, because the bill itself shows the mix (A-91).

**The rule expires after one full bill cycle on the new plan.** The next bill's usage
period starts on or after `d`, so it is not mixed and is simulated normally. A change on
exactly `periodStart` is a whole period on the new plan and is not mixed.

Seed: 1004 September (16 Aug – 15 Sep, change on 1 Sep) → `RECENT_PLAN_CHANGE`. Its
October bill would be simulated normally. Without this rule the simulator would report
"`PP_399` saves ₹300.00 + GST" at 39 GB, which contradicts the scenario (a legitimate,
customer-chosen upgrade).

### 3.3 The plan of the period

The plan billed in the period is the active plan from TMF622, unless a completed plan
change took effect after the period ended; then it is that change's `fromCode`.

### 3.4 Re-rating

Only usage with `usage_period = billed_period` is re-rated; late usage stays as billed
(A-88). For a plan `p` and an optional add-on `a`, the plan-dependent cost is:

```
cost = rental(p) [+ price(a)]
     + over(DATA_MB, DOMESTIC)   + over(VOICE_MIN, DOMESTIC) + over(SMS, DOMESTIC)
     + over(ISD_MIN, INTL)
     + Σ per roaming band b: over(ROAM_DATA_MB, b) + over(ROAM_VOICE_MIN, b) + over(ROAM_SMS, b)
over(type, band) = round(max(0, usage − included − addOnAllowance) × unitPrice)   (included NULL = unlimited → 0)
```

- ISD minutes come from the new `usage_period.isd_min` column (Q-28). `voice_min` is now
  domestic minutes only. The catalogue has no separate international SMS tariff, so there
  is no `isd_sms` column (A-89).
- A roaming country is mapped to a band by config (`billshock.catalog.roaming-bands`,
  A-90). The seed maps `AE` to `GCC`.
- A charge is rounded per usage type and band, as the bill's usage lines are.
- A plan that lacks a tariff for a usage type with non-zero usage cannot be rated and is
  skipped (for the current plan: `NOT_SIMULATABLE`, `CURRENT_PLAN_NOT_RATABLE`). A plan
  whose rating would need a day-based tariff is skipped too (for the current plan:
  `NOT_SIMULATABLE`, `DAY_BASED_TARIFF`).

**Plan-independent charges** (VAS, adjustments, credits, one-off charges, and a duplicate
line) are excluded from every figure. Savings are therefore measured against the
**re-rated current plan**, not the billed total (seed-scenarios.md §7.1). For 1005 the
re-rated current plan is ₹599.00, not the billed ₹1,198.00, so no plan shows a false
"saving"; the ₹599.00 belongs to the dispute.

### 3.5 Options

- **Plans:** every catalogue plan valid on the period's last day, other than the current one.
  `savingExclGst = cost(current) − cost(plan)`. Only a saving **> 0** is returned. They are
  ranked by saving descending, then the lower rental, then the plan code. At most 3
  (`billshock.analysis.simulator.max-plan-options`, Q-21). An empty list means that no plan
  would have been cheaper (US-REC-01).
- **Add-on:** each add-on valid on the period's last day, applied once to the **current**
  plan (no multiples, Q-21):
  - a domestic add-on (`BILL_CYCLE`) adds its data, voice and SMS allowances to the
    domestic usage of the period;
  - a roaming pack (`DAYS`, a band) applies to the roaming usage of its own band only, and
    only if the trip span in that band (earliest first day to latest last day, inclusive)
    is at most its validity in days; beyond its allowances, usage is charged at the
    pay-per-use rates.

  The best add-on is the one with the highest saving **> 0**, then the lower price, then
  the code (A-92).
- **GST:** every option carries `totalInclGst` = cost + GST under the bill's supply type,
  and `savingInclGst = totalInclGst(current) − totalInclGst(option)`, which is the
  difference the customer would have seen.

### 3.6 Expected results on the seed (September 2026)

| Account | Status | Current re-rated | Plans | Best add-on |
|---|---|---|---|---|
| 1001 | `SIMULATED` | ₹2,474.00 | none | `IR_GCC_7D`: saving ₹876.00 / **₹1,033.68 incl. GST** |
| 1002 | `SIMULATED` | ₹767.64 | `PP_499` 268.64, `PP_599` 168.64, `PP_699` 68.64 (incl. GST 588.82 / 706.82 / 824.82) | `DATA_10GB`: cost 712.84, saving 54.80, total 841.15 |
| 1003 | `SIMULATED` | ₹499.00 | none | none |
| 1004 | `RECENT_PLAN_CHANGE` | — | — | — (change 2026-09-01, `PP_399` → `PP_699`) |
| 1005 | `SIMULATED` | ₹599.00 | none | none |
| 1006 | `SIMULATED` | ₹523.00 (incl. 4 ISD min) | none | none |

## 4. Guardrail chain (Chain of Responsibility, SPEC §4.2, §4.5; ADR-004)

### 4.1 The facts come from the server, never from the LLM

**`GuardrailContext` is always built server-side, from the read models and the BSS
gateways, never from LLM tool arguments.** This is enforced by the types:

- `GuardrailService.evaluate(AccountId, ActionRequest)` is the only entry point. The
  `AccountId` comes from the SecurityContext (5a/6a), never from the LLM.
- `ActionRequest` records carry only what the LLM may legitimately choose: which line
  items, which subscription, which plan or add-on, a proposed goodwill amount, and free
  text. They have no field for a bill total, a supply type, opt-in evidence, credit
  history, a duplicate flag or a refund amount.
- `GuardrailContext` and `GuardrailContextFactory` are internal to `actions` (not part of
  the module's API), and no public method accepts a context.
- The factory looks every referenced id up **under the caller's account**. A line item,
  subscription, plan or add-on that is not found is rejected (`UNKNOWN_*`), so another
  account's ids cannot be used.
- Amounts that the server can determine are computed by the server: the VAS refund is the
  sum of that subscription's charges in the local history; the dispute amount is the sum
  of the cited lines; GST is always computed from the bill's supply type.

`GuardrailServiceTest` checks this (addition 1): reflection on every `ActionRequest`
component and on the public API, plus behaviour with a lying request (another account's
line item, a refund for an opted-in VAS, a credit on a duplicate).

### 4.2 Context

| Fact | Source |
|---|---|
| The bill in question | The bill that holds the cited line items (they must all be on one bill); otherwise the latest bill |
| Line items of the account | `BillingReadModel`, for the bills of the last 7 billing months up to the latest bill |
| Duplicates on that bill | `DuplicateChargeDetector` over its line items |
| Prior credits | CREDIT lines, or negative ADJUSTMENT lines, on the bill in question or the 6 billing months before it (A-85). 6a adds executed `ProposedAction` credits |
| Subscription and opt-in evidence | `ProductInventoryGateway.activeProducts` (TMF622) |
| Plans and add-ons | `CatalogReadModel`, valid today in IST (A-95) |
| Autonomy level | `billshock.guardrails.autonomy-level` (fixed at 1 in the MVP slice) |

### 4.3 Decision

`GuardrailDecision` has an **outcome** (`PROPOSE`: create a `PENDING_CONFIRMATION`
proposal; `ESCALATE`: create it as `ESCALATED` and hand off; `REJECT`: create nothing),
**reason codes** (never thresholds, SPEC §4.5), `supervisorRequired`,
`autoApprovalEligible` (the Level 2 policy), `autoApprove` (eligible **and** the autonomy
level is 2 or more; always `false` at Level 1), the **server-computed amount** (excl. GST,
GST, incl. GST) where there is one, and the line items it covers.

### 4.4 The chain

Handlers run in this order. A `REJECT` or `ESCALATE` stops the chain; flags accumulate.

| # | Handler | Applies to | Rule |
|---|---|---|---|
| 1 | `AutonomyCheck` | all | Level 0 → `REJECT ACTIONS_DISABLED` (at Level 0 the tools are not registered at all, ADR-004; this is defence in depth) |
| 2 | `ReferenceCheck` | all | Every line item, subscription, plan and add-on must exist under the account (`UNKNOWN_LINE_ITEM`, `UNKNOWN_SUBSCRIPTION`, `UNKNOWN_PLAN`, `UNKNOWN_ADD_ON`); cited lines must be on one bill (`LINE_ITEMS_ON_SEVERAL_BILLS`); TAX lines cannot be cited (`TAX_LINE_CITED`); a plan change to the current plan → `ALREADY_ON_PLAN` |
| 3 | `GoodwillCreditCheck` | goodwill credit | See §4.5 |
| 4 | `VasCheck` | VAS unsubscribe | The subscription must be a VAS (`NOT_A_VAS`). A refund needs missing double opt-in: with a complete double opt-in → `REJECT REFUND_NOT_ELIGIBLE_OPT_IN_PRESENT` (the agent may propose unsubscribe without refund). No local charges and not older than the history → `REJECT NO_REFUNDABLE_CHARGES`. Refund amount: §4.6. A refund is policy-mandated (SPEC §4.5), so it is **exempt from the goodwill bill-share, ₹500 and prior-credit rules, but not from the money-out cap** (§4.5a): above ₹2,000.00 incl. GST → `ESCALATE MONEY_OUT_ABOVE_ESCALATION_LIMIT` |
| 5 | `PlanChangeCheck` | plan change | Always `PROPOSE` with customer confirmation; never auto-approval-eligible, at any level (SPEC §4.5) |
| 6 | `DisputeCheck` | dispute | Needs at least one line item (`NO_LINE_ITEMS`); amount = Σ cited lines, with GST |
| 7 | `EscalationCheck` | escalate to human | Outcome `ESCALATE` |

Third-party barring and add-on purchases pass with `PROPOSE` and need confirmation. Only a
goodwill credit can be auto-approval-eligible (the Level 2 policy in SPEC §4.5 covers
credits only).

### 4.5 Goodwill credit (Q-20: thresholds on the GST-inclusive amount)

**How A-94 and Q-20 fit together.** The goodwill tool passes `amountExclGst`, copied from a
tool result (A-94), because the LLM never computes GST (ADR-003). **Every threshold is
compared with the GST-inclusive amount** (Q-20). The chain first computes that amount
itself: `incl = amountExclGst + GstCalculator.compute(amountExclGst, bill.supplyType)`, the
bill-level rule of A-72 (CGST + SGST, each rounded HALF_EVEN, for intra-state; IGST for
inter-state). The amount before GST is never compared with a threshold. Because the supply
type changes the GST by up to a paisa, the same input can land on different sides of a limit:
₹1,694.92 before GST is ₹2,000.00 intra-state (allowed) but ₹2,000.01 inter-state
(escalated). Tests: `GuardrailServiceTest.ThresholdsUseTheGstInclusiveAmount`.

With `incl` = the GST-inclusive credit and `bill` = the bill total:

1. `incl ≤ 0` → `REJECT INVALID_AMOUNT`.
2. Any cited line is a detected duplicate → `REJECT USE_DISPUTE` (US-RES-03: a billing
   error is disputed, not compensated with goodwill).
3. `incl > bill` → `REJECT EXCEEDS_BILL`.
4. `incl > ₹2,000.00` → `ESCALATE MONEY_OUT_ABOVE_ESCALATION_LIMIT` (the money-out cap, §4.5a).
5. `incl > 15% of bill` (rounded HALF_EVEN) **or** `incl > ₹500.00` **or** a prior credit
   → `PROPOSE` with `supervisorRequired` (reasons `ABOVE_BILL_SHARE`,
   `ABOVE_AUTO_APPROVAL_LIMIT`, `PRIOR_CREDIT`).
6. Otherwise `PROPOSE` and `autoApprovalEligible`.

All limits are inclusive (≤ passes): a credit of exactly 15%, ₹500.00 or ₹2,000.00 stays
in the lower band (A-84). Config: `billshock.guardrails.goodwill.*`.

Seed: 1001, bill ₹2,919.32 → 15% = ₹437.90.

### 4.5a Money-out cap (gate review 4a, item 4)

**No money-out action is uncapped.** Every action that pays money to the customer, which
today means a goodwill credit or a VAS refund, is escalated to a human when its
GST-inclusive amount is above `billshock.guardrails.money-out-escalate-above-inr` (₹2,000.00,
inclusive: exactly ₹2,000.00 is allowed). `MoneyOutCap` implements this, and both
`GoodwillCreditCheck` and `VasCheck` use it. A dispute moves no money (it opens a TMF621
ticket), so the cap does not apply to it.

### 4.6 VAS refund amount (A-86)

The refund covers **all charges of that subscription in the local bill history** (7 billing
months): excl. GST per bill, GST per bill under that bill's supply type, summed. For 1003
"Astro Daily": 4 lines, ₹196.00 + ₹35.28 = **₹231.28 incl. GST**.

The money-out cap (§4.5a) applies to this refund amount: exactly ₹2,000.00 incl. GST is
proposed and ₹2,000.01 is escalated (`GuardrailServiceTest.VasRefundCap`).

**Limitation:** if the subscription was activated before the start of the local history
(the earliest local bill's period start), the local charges are not the full amount. The
decision then sets `refundAmountIncomplete` and the full refund amount must come from BSS
(TMF678/TMF635). Fetching it is not built in the MVP slice.

## 5. Tool-call budget per turn (Q-29; llm-architecture.md §9)

`ToolCallBudget` is a per-turn counter with no Spring AI dependency:

- `admit(toolName)` returns `ALLOWED`, `ALREADY_CALLED_THIS_TURN` (a once-per-turn tool
  called again: not executed, the turn continues) or `LIMIT_REACHED` (the 9th counted call:
  stop the loop, escalate and answer from the template).
- Counted calls: every tool except `escalateToHuman` and `recordDiagnosis`; limit 8.
- `escalateToHuman` and `recordDiagnosis`: at most once each per turn, and still allowed
  after the limit (so the agent can always escalate).
- Config: `billshock.agent.tool-calls.max-counted-per-turn=8`,
  `…once-per-turn=[escalateToHuman, recordDiagnosis]`.

5a wraps `DefaultToolCallingManager` in `TurnToolBudget`, which asks this policy before each
call, and keeps the built-in `maxTotalToolCalls(10)` as a backstop.

## 6. INR formatting (Q-30)

`InrFormat` formats amounts for tool results, templates and notifications, with Indian
digit grouping (en-IN: the last three digits, then groups of two):

| Method | Example |
|---|---|
| `amount(Money)` | `₹1,23,456.78`; negative `-₹12.00` |
| `inclGst(Money)` | `₹2,832.00 incl. GST` |
| `exclGst(Money)` | `₹2,400.00 excl. GST` |
| `plusGst(Money)` (catalogue prices) | `₹599 + GST`; `₹29.50 + GST` (whole rupees without paise, as in SPEC §4.6) |

The grouping is implemented in code: `java.text.DecimalFormat` supports only one grouping
size, so it cannot produce lakh grouping.

## 7. Tests

- **Unit** (`./mvnw test`, no Docker): in-memory fakes of the read models and gateways built
  from the seed values (`SeedScenarioFixtures`); every expectation of seed-scenarios.md §5.7
  (chat column), §7 (causes) and §3.6 above; rounding-reconciliation cases with odd
  baselines; guardrail boundaries (exactly 15%, ₹500.00 / ₹500.01, ₹2,000.00 / ₹2,000.01, for
  goodwill and for VAS refunds); thresholds compared on the GST-inclusive amount;
  the server-side-facts test (§4.1); the tool-call budget; the formatter.
- **Integration** (`AnalysisSeedIT`, `GuardrailSeedIT`): the same expectations against the
  seeded PostgreSQL through the real read models and mock gateways.
