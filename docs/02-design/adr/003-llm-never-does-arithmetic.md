# ADR-003: The LLM never does arithmetic

| | |
|---|---|
| Status | Accepted (SPEC §4.3 rule 1) |
| Date | 2026-09-25 |
| Deciders | Repo owner |
| Related | [llm-architecture.md](../llm-architecture.md) §7, risk R-01, NFR-16 |

## Context

The product's core outputs are money: the excess on a bill, each driver's contribution,
the savings of a plan change, and credit amounts. LLMs are unreliable at arithmetic and
may state figures that look plausible. A wrong ₹ figure is the highest-ranked risk
(R-01), and a wrong credit is a direct financial loss (R-02).

## Options

| Option | For | Against |
|---|---|---|
| A. The LLM computes from raw usage and line items | Flexible | Unreliable arithmetic; not auditable; cannot be unit-tested |
| B. The LLM computes and a checker verifies afterwards | Some flexibility | Still invents numbers; the checker has to recompute everything anyway |
| **C. Deterministic Java engines compute every amount; the LLM selects tools and explains results** | Exact (`BigDecimal`), unit-testable, auditable; the fallback template can render the same numbers without an LLM | The LLM cannot answer "what if" questions that no engine covers. It must say so or escalate |

## Decision

**Option C.**

- `BillDiffEngine`, `AnomalyDetector` and `PlanSimulator` (the `analysis` module) and the
  guardrail chain (`actions`) compute every amount in Java with `BigDecimal`,
  `RoundingMode.HALF_EVEN`, scale 2.
- Tools return **precomputed, already-rounded values with their labels** (for example
  `roamingExcess: 2450.00`), plus the line-item ids that support them.
- The LLM output contract (`BillShockDiagnosis`) references amounts that tool results
  produced. An **output grounding check** in Java (llm-architecture.md §7) extracts every
  ₹ amount from the text and structured output. Any amount that does not match a value in
  this turn's tool results (after normalising format) fails the check. The turn is then
  re-rendered through the deterministic template, and the event is counted as
  `grounding_violation`.
- Tool arguments that carry money (for example `proposeGoodwillCredit(amount, …)`) are
  re-validated by the guardrail chain. The amount must be ≤ the value the engine computed
  for the cited line items.

## Consequences

- Positive: every ₹ figure can be traced to an engine result recorded in the audit log.
- Positive: the deterministic fallback (SPEC §2.6) is possible because the LLM adds no
  numbers.
- Negative: questions outside the engines' coverage get "I can't calculate that here"
  or an escalation. This is intended.
- Negative: the grounding check can produce false alarms on legitimate derived phrases
  (for example "about ₹2,500"). The prompt asks for exact figures only; approximations
  are not allowed, which keeps the check strict.
