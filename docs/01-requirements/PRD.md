# Product Requirements Document: Bill Shock Advisor (v1)

| | |
|---|---|
| Status | Draft for review (Phase 1) |
| Date | 2026-09-25 |
| Spec reference | SPEC.md §1.1, §1.2, §4.3–4.5, §8.2 |
| Related | [assumptions.md](assumptions.md), [feasibility.md](feasibility.md), [capacity-estimates.md](capacity-estimates.md), [plan-and-budget.md](plan-and-budget.md), [../02-design/nfr.md](../02-design/nfr.md) |

## 1. Problem

"Bill shock" is a bill much higher than the customer expects (for example 3x the usual
amount). Common causes:

- international roaming without a pack
- domestic data overage
- third-party / value-added service (VAS) subscriptions
- mid-cycle plan changes (proration)
- billing errors, such as duplicate line items
- late-arriving roaming charges from a previous period's trip, billed in the current
  cycle (roaming usage can arrive days to weeks late; A-57)

Bill shock drives care-centre volume, disputes, churn and regulatory complaints. Today's
chatbots only answer FAQs. They cannot look at the customer's own bill, work out *why* it
went up, or fix it.

## 2. Product goal

An AI agent that, for a postpaid consumer's bill:

1. **Investigates:** pulls the current and historical bills, usage and subscriptions.
2. **Diagnoses:** identifies and quantifies each driver of the increase.
3. **Recommends:** re-rates actual usage against the plan catalog and shows exact savings.
4. **Resolves:** carries out approved actions (credit, VAS unsubscribe, plan change,
   dispute) within guardrails.
5. **Prevents:** detects anomalies when the bill is generated and reaches out first.

Product principles (non-negotiable, from SPEC 4.3):

- **Every amount is computed deterministically.** The LLM explains; Java engines calculate
  (BigDecimal, HALF_EVEN).
- **Nothing that changes the account happens without confirmation** or an explicit policy
  auto-approval.
- **Answers are grounded.** The agent cites only charges that appear in tool results. When
  data is missing, it says so.
- **Normal bills stay normal.** The agent must never invent an issue.

## 3. Personas

### P1: Postpaid consumer ("Priya")
- An individual postpaid mobile customer who uses the app/web self-care.
- **Goal:** understand why this month's bill is high and fix it quickly, without calling care.
- **Pain:** jargon-filled bills; long waits in call queues; distrust of "the system".
- **Needs:** plain language, ₹ amounts, clear choices, and control over what happens.

### P2: Care agent, assisted mode ("Arjun")
- A contact-centre agent handling a bill-shock call or chat.
- **Goal:** resolve the contact on first touch with a correct explanation and the right
  remedy.
- **Pain:** digging through several BSS screens; inconsistent goodwill decisions.
- **Needs:** a ready-made diagnosis, recommended actions within policy, and a one-click
  proposal that the customer confirms. Per A-40, the agent may confirm on the customer's
  behalf only with a recorded verbal-consent flag.

### P3: Billing operations supervisor ("Meera")
- Owns billing quality, goodwill budget and escalations.
- **Goal:** keep credits within policy and catch systemic billing errors early.
- **Needs:** approve or reject flagged credits, see the audit trail per conversation,
  monitor ₹ credited per hour, and switch autonomy levels off instantly.

## 4. Scope

### 4.1 In scope (v1)
- Postpaid consumer accounts, INR, 18% GST.
- Channels: web/app chat (SSE streaming), proactive in-app/push notification (A-39), and
  care-agent assisted mode on the same API.
- The 5 capabilities above, for the 6 reference scenarios in §7.
- Autonomy ladder Level 0 → 1 → 2 (SPEC 8.2), with a kill switch at each level.

### 4.2 Out of scope (v1)
- Prepaid accounts.
- Enterprise / corporate accounts.
- Voice channel (IVR / voice bot).
- Multilingual conversations. **Design constraint:** all customer-facing text comes from
  versioned prompt files and message templates keyed by locale (`en-IN` in v1), so Hindi
  (`hi-IN`) can be added as new template and prompt files without code changes. Money and
  date formatting is locale-aware.
- Changing tariffs or billing rules in the BSS. The agent only uses existing plans, add-ons
  and adjustment types.
- Collections and payment disputes (card chargebacks).

## 5. User stories and acceptance criteria

Notation: **Given / When / Then**. "Tool result" means the output of a deterministic
tool (SPEC 4.4). All ₹ amounts in acceptance criteria must match the engine output
exactly.

### 5.1 INVESTIGATE

**US-INV-01: Ask why my bill is high (P1)**
As a consumer, I want to ask "why is my bill so high?" and have the agent look at my
actual bill, so I get an answer about *my* account, not an FAQ.
- Given an authenticated customer with a current bill,
  when they ask about a high bill,
  then the orchestrator runs `diffBills` for the current period **before the first LLM
  call** and streams a deterministic one-line summary (total excess + top driver) with
  p95 < 1.5 s. The LLM's explanation follows, grounded in that result (SPEC 4.6, NFR-01a/b).
- The account investigated is always the one in the caller's token. A request to look at
  another number or account is refused and audited (SPEC 4.3 rule 2).
- If a BSS call fails, the agent says which data is unavailable. It does not guess.
- Every tool call is written to the audit log with conversation id and prompt version.

**US-INV-02: See usage detail behind a charge (P1, P2)**
As a consumer, I want to see the usage behind a charge (for example roaming data by day
and country), so I can recognise it.
- Given a charge line in the diagnosis, when the user asks for detail, then the agent
  calls `getUsageDetails` for that period and type.
- Detailed usage is fetched from BSS on demand and cached only for the conversation
  (A-25).
- MSISDNs are shown masked (last 4 digits) in any LLM-visible data (SPEC 2.5).

**US-INV-03: Review active subscriptions (P1, P2)**
As a consumer, I want to see which plan, add-ons and VAS I pay for, with activation date,
channel and opt-in record, so I can spot things I did not ask for.
- `getActiveSubscriptions` returns activation date, channel and opt-in evidence for each
  item. Where opt-in evidence is missing, the response says so explicitly.

### 5.2 DIAGNOSE

**US-DIA-01: Get a quantified explanation (P1)**
As a consumer, I want a short explanation with ₹ amounts, largest cause first.
- The answer is 2–4 plain sentences, largest driver first, with ₹ amounts taken from
  `diffBills` (SPEC 4.6).
- The structured `BillShockDiagnosis` has `causes[]` (each with category, amount and
  supporting line-item ids), `totalExcess`, `confidence` and `recommendedActions[]`.
- Σ `causes[].amount` reconciles with `totalExcess` within ₹0.01, as computed by the
  engine (not the LLM). **Decision Q-23:** each cause carries its amount before GST, its
  GST and its GST-inclusive amount; Σ of the GST-inclusive amounts equals the increase in
  the bill total exactly.
- Every amount in the answer says whether it includes GST. A cause is named by its
  GST-inclusive amount (what the bill total shows), for example "₹2,094.50 incl. GST".
  The strings are formatted in Java and copied verbatim by the model (llm-architecture.md
  §10).
- Jargon (proration, VAS, roaming pack) is explained in plain words on first use.

**US-DIA-02: A normal bill is called normal (P1)**
As a consumer with a normal bill, I want to be told it is normal, not sold a problem.
- Given scenario 6 (normal bill), the diagnosis contains **zero** causes flagged as
  anomalous, and the agent proposes no remedial action (NFR "0 invented issues").

**US-DIA-03: Assisted-mode diagnosis (P2)**
As a care agent, I want the same diagnosis on my screen within seconds of opening the
customer's account, so I can explain it on the call.
- `GET /api/v1/bills/{period}/diagnosis` returns the cached diagnosis for the bill if one
  exists (computed once per `bill_id`, SPEC 2.3); otherwise it computes it.
- Non-LLM API p95 < 300 ms when the diagnosis is cached (see nfr.md).

**US-DIA-04: Graceful degradation (P1)**
As a consumer, I still want an explanation when the AI service is down.
- When the LLM is unavailable (timeout, 429, 5xx or open circuit breaker), the system
  returns the deterministic diagnosis rendered through a template, for example "Your bill
  is higher mainly because of roaming charges of ₹X…" (SPEC 2.6).
- The fallback response is marked as such in logs and metrics (fallback rate).

### 5.3 RECOMMEND

**US-REC-01: Better-fit plan with exact savings (P1)**
As a consumer, I want to know whether another plan would have been cheaper for my actual
usage.
- `simulatePlans` re-rates the actual usage of the period against the catalog and returns
  the top 3 by savings. Each result shows plan, simulated total and saving in ₹, computed
  in Java.
- The agent offers **at most 3** options, and each is quantified (SPEC 4.6).
- If no plan saves money, the agent says so.

**US-REC-02: Right add-on for recurring behaviour (P1)**
As a frequent roamer, I want to know which pack would have avoided the spike.
- Given scenario 1 (UAE roaming, no pack), the recommendations include the relevant
  roaming add-on with the ₹ difference against the charges actually incurred.

**US-REC-03: Policy-grounded answers (P1, P2)**
As a user, I want policy questions (refund rules, dispute timelines) answered from the
operator's policy documents.
- `getPolicy` answers are grounded in retrieved policy passages. If nothing relevant is
  retrieved, the agent says it cannot find a policy and offers escalation.

### 5.4 RESOLVE

**US-RES-01: Propose, then confirm (P1)**
As a consumer, I want to approve any change before it happens.
- Action tools create a `ProposedAction` in state `PENDING_CONFIRMATION` only (SPEC 4.3
  rule 3).
- The agent states clearly what needs confirmation and what, if anything, was done
  automatically.
- Execution happens only through `POST /api/v1/actions/{id}/confirm` with an
  `Idempotency-Key`. Confirming twice never executes twice (SPEC 4.3 rule 4).
- A plan change always requires explicit confirmation (SPEC 4.5).

**US-RES-02: Refund for VAS I never opted into (P1)**
- Given scenario 3 (third-party VAS without a double opt-in record), the agent proposes
  unsubscribe **and** a full refund of the VAS charges (SPEC 4.5), and offers
  third-party barring.

**US-RES-03: Dispute a billing error (P1, P3)**
- Given scenario 5 (duplicate line item), the agent identifies the duplicate lines by id
  and proposes `raiseDispute` with those line-item ids. It does not issue a goodwill
  credit for the same amount.

**US-RES-04: Goodwill within policy (P1, P3)**
- Auto-approval (Level 2 only) requires **all** of: credit ≤ 15% of the bill, ≤ ₹500, and
  no credit in the last 6 months (SPEC 4.5, configurable).
- Above that: customer confirmation plus a supervisor flag. Above ₹2,000: escalate.
- Guardrail checks run in Java (chain of responsibility). The LLM never sees the numeric
  thresholds and must not reveal them (SPEC 4.5).

**US-RES-05: Supervisor review (P3)**
As a supervisor, I want a queue of flagged proposals and the full audit trail per
conversation.
- `GET /api/v1/actions?status=` lists proposals by status, paginated.
- `GET /api/v1/audit?conversationId=` is restricted to SUPERVISOR.
- Every approve/reject records who, when and why.

**US-RES-06: Hand-off to a human (P1, P2)**
- When the guardrails require it, after 8 tool calls in a turn, or when the customer asks,
  the agent calls `escalateToHuman` with a summary, so the human does not re-ask the same
  questions.

**US-RES-07: Kill switch (P3)**
- A supervisor/admin can drop the autonomy level (2 → 1 → 0) at runtime via a feature
  flag, without a redeploy. The change takes effect on the next turn and is audited.

### 5.5 PREVENT

**US-PRE-01: Proactive heads-up (P1)**
As a consumer, I want to be told about an unusual bill before I discover it myself.
- On `bill.generated`, the anomaly detector (deterministic) screens every bill. Flagged
  bills get a diagnosis (the current Haiku-tier model (config) writes the explanation) and a
  notification.
- All flagged bills of a cycle are diagnosed within 6 h (NFR).
- The notification links to the diagnosis. Opening chat from it reuses the cached
  diagnosis (no recomputation).

**US-PRE-02: No noise on normal bills (P1)**
- Bills not flagged by the detector produce no notification. The eval set has 0 false
  issues on normal bills (NFR).

**US-PRE-03: Batch must not starve live chat (P3)**
- The proactive worker uses a bounded LLM concurrency (semaphore) and a separate
  workspace/quota from live chat (SPEC 2.3, A-21).

## 6. KPIs

Baselines are unknown (A-43). Targets are placeholders to be set after a pilot. All KPIs
are reported per autonomy level.

| ID | KPI | Definition | Data source | Placeholder target |
|---|---|---|---|---|
| K-01 | **Resolved without a human** (containment) | % of bill-shock conversations with no `escalateToHuman` **and** no human-assisted contact about the same `bill_id` within 7 days | Conversations, audit, CRM contact history (A-44) | Level 1: 40% after 3 months (A-38) |
| K-02 | Average handling time (AHT) reduction | For assisted-mode and escalated contacts: AHT of bill-shock contacts versus the pre-launch baseline | Contact-centre platform | −20% versus `TBD-baseline` |
| K-03 | Dispute rate | Formal billing disputes per 10,000 bills in the cycle. Split into *valid* (billing error confirmed) and *avoidable* (explained usage) | Trouble tickets (TMF621) | Avoidable disputes −25% |
| K-04 | CSAT | Mean rating of post-conversation feedback (`POST /api/v1/feedback`), plus thumbs-down rate | Feedback table | ≥ `TBD-baseline` + 0.3 (5-pt scale) |
| K-05 | Cost per resolved case | (LLM + infra cost attributed to chat) ÷ conversations counted as resolved in K-01 | Cost metrics, K-01 | Below the human contact cost (A-35); see feasibility §3 |
| K-06 | **Repeat contact rate (7 days, same bill)** | % of bill-shock conversations followed by **another contact on any channel about the same `bill_id` within 7 days**. Reported separately for AI-resolved and escalated conversations | Conversations + CRM contact history (A-44) | AI-resolved: ≤ `TBD-baseline` of human-resolved contacts |
| K-07 | **Proactive outreach acceptance rate** | Of proactive notifications that carried ≥ 1 recommendation, % where the customer **accepts a recommendation within 7 days**. At Level 1–2, acceptance = a confirmed `ProposedAction` from that notification. At Level 0, acceptance = a click-through to the recommended option. Supporting metric: open rate | Notifications, actions, click events | `TBD` after pilot |

Quality guardrail metrics (from NFRs, not business KPIs): diagnosis accuracy ≥ 95%, 0
invented issues on normal bills, LLM fallback rate, and ₹ auto-credited per hour.

## 7. Reference scenarios (seed data, SPEC 4.10)

| # | Scenario | Expected primary cause | Expected remedy |
|---|---|---|---|
| 1 | UAE roaming, no pack (~3.5x) | International roaming | Roaming add-on recommendation; goodwill per policy |
| 2 | Domestic data overage | Data overage | Plan or add-on with higher data, with savings |
| 3 | Third-party VAS, no opt-in record | VAS charges | Unsubscribe + full refund; offer third-party barring |
| 4 | Mid-cycle plan upgrade | Proration | Explanation only (legitimate charge) |
| 5 | Duplicate line item | Billing error | Dispute on the duplicate line ids |
| 6 | Normal bill | None | No action; say it is normal |
| 7 | Late-arriving roaming charges from a previous period's trip (scheduled for Phase 6b, not in the MVP slice) | Late roaming charges (prior period) | Explain that they belong to the earlier trip (period, country, amount) and are not new roaming; goodwill per policy |

## 8. Release plan by autonomy level (SPEC 8.2)

| Level | Behaviour | Entry criteria (proposed) |
|---|---|---|
| 0: Explain only | Diagnosis + recommendations; no action tools | All NFR gates pass in staging; eval accuracy ≥ 95%; legal review of R-08/R-09 (feasibility) |
| 1: Propose | Action proposals with customer confirmation | 4 weeks at Level 0 with fallback rate < 5%, thumbs-down within target, no grounding incidents |
| 2: Auto-approve | Small credits within policy | 4 weeks at Level 1; credit accuracy audit by P3; ₹/hour alert tested |

## 9. Dependencies

- BSS APIs modelled on TM Forum: TMF678 Customer Bill, TMF635 Usage, TMF620 Product
  Catalog, TMF622 Product Ordering, TMF621 Trouble Ticket. v1 ships with mock gateways
  (SPEC 4.1).
- VAS opt-in evidence (may live in a separate consent platform; see feasibility §2.1).
- CRM contact history for K-01 and K-06 (A-44).
- Identity provider (OIDC; Keycloak locally).
- Anthropic API (or a cloud-provider route, see R-05).

## 10. Glossary

| Term | Meaning |
|---|---|
| Bill shock | A bill much higher than the customer's usual bill |
| VAS | Value-added service, often a third-party content subscription billed to the phone bill |
| Double opt-in | A second, explicit confirmation from the customer before a VAS subscription starts |
| Proration | Charging part of a monthly fee for part of a bill cycle, for example after a plan change |
| Re-rating | Recalculating charges for actual usage under a different plan |
| MSISDN | The mobile number |
| ProposedAction | A pending, not-yet-executed change awaiting confirmation or policy approval |
| Containment | A contact resolved without human involvement |
