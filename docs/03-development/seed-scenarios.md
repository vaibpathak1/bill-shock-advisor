# Seed Scenarios (Phase 3a)

| | |
|---|---|
| Status | **Approved** (owner, 2026-09-25; Q-20 to Q-26 decided) |
| Date | 2026-09-25 |
| Spec reference | SPEC.md §4.5, §4.10, §11 (3a); PRD §5, §7 |
| Related | [data-architecture.md](../02-design/data-architecture.md) §2–§5, ADR-003, ADR-007, [assumptions.md](../01-requirements/assumptions.md) A-71 to A-83 |

This document specifies the seed data: 6 demo customers with 7 bills each (current +
6 prior), 8 plans and 5 add-ons. Every amount below was computed with `BigDecimal` and
HALF_EVEN rounding (script in the session scratchpad, not in the repo), not by hand. These
numbers are the **test values for Phase 4a** (`BillDiffEngine`, `PlanSimulator`,
guardrails) and for the scenario E2E tests in 6a. The seed SQL and its tests must
reproduce every total here exactly.

All names are fictional. Plans, add-ons and VAS providers use generic names; no real
operator, brand or content provider is modelled.

---

## 1. Calendar and partitions (decisions: answers 1 and 2)

- **Billing months:** March to September 2026. `bill_period` = the first day of the month
  of the bill date (data-architecture.md §3). **September 2026 is the current bill.**
  "Current" means the latest bill, not today's date, so tests and the demo do not change
  over time.
- **Seven bills per customer:** the current bill plus 6 prior (answer 2). This matches the
  7 partitions kept (data-architecture.md §5).
- **Bill cycle days:** 1, 6, 11, 16 and 21 (A-71). The usage period runs from the cycle
  day of the previous month to the day before the bill date. For example, cycle day 16:
  the September bill is dated 16 Sep 2026 and covers 16 Aug to 15 Sep. All September bill
  dates are on or before 25 Sep 2026.
- **Partitions:** monthly, **2026-01 to 2027-12** for every partitioned table (`bill`,
  `bill_line_item`, the four usage tables, `chat_messages`, `llm_call_log`,
  `audit_events`). No DEFAULT partition, so a row outside this range fails (tested).
- **Daily usage rows** exist only for the current and previous billed periods (August
  and September 2026, A-60). Period aggregates exist for all 7.
- **No late usage** in the MVP seed. `usage_period = billed_period` on every row. The late
  roaming scenario 7 is seeded in 6b.

## 2. GST model (addition B)

Modelled on an Indian telecom tax invoice (A-72, **FOR TAX REVIEW**):

1. **Taxable value** = the sum of all non-tax line items on the bill (`bill.subtotal`).
2. **Tax is computed once, on the bill-level taxable value**, not per line item.
3. **Intra-state supply** (the account's GST state = the supplier's registration state):
   **CGST 9% + SGST 9%**, two TAX line items. **Inter-state supply:** **IGST 18%**, one
   TAX line item.
4. **Each tax component is rounded HALF_EVEN to 2 decimals on its own.** CGST and SGST
   are each rounded, and the two are not derived from a rounded 18% figure.
5. `bill.tax_total` = the sum of the TAX lines; `bill.total` = `subtotal + tax_total`.
   Both are checked at ingest and in the seed tests.

**Place of supply:** for a postpaid mobile connection this is taken to be the state of the
customer's billing address (A-72; the legal basis is to be confirmed by tax review). Each
account carries its GST state code (**proposed schema change**, §8). The supplier's
registration states are a config list: `billshock.tax.supplier-state-codes=[27]` (Maharashtra only) in the
seed (A-73). A real operator is registered in every state it serves and would mostly
invoice intra-state. The seed mixes both cases so that both code paths are exercised.

**Who computes GST:** the BSS issues the bills, so the engines use the tax lines on the
bill as given. We apply the same rule only for (a) the ingest/seed consistency check,
(b) `PlanSimulator` totals including GST, and (c) the GST part of proposed refunds and
credits, which **include GST** (decision Q-20).

### Worked examples (all appear in the seed)

| Case | Taxable | Calculation | HALF_EVEN | HALF_UP would give | Note |
|---|---|---|---|---|---|
| C3 Mar–Aug, intra | ₹528.50 | 9% = 47.5650 | CGST **47.56** + SGST **47.56** = **95.12** | 47.57 each | A true tie: HALF_EVEN keeps the even digit |
| C3 September, intra | ₹724.50 | 9% = 65.2050 | CGST **65.20** + SGST **65.20** = **130.40** | 65.21 each | A tie again |
| Same ₹724.50 if inter-state | ₹724.50 | 18% = 130.4100 | IGST **130.41** | 130.41 | Two rounded halves (130.40) ≠ one rounded whole (130.41), so the supply type matters to the paisa |
| C2 September, inter | ₹767.64 | 18% = 138.1752 | IGST **138.18** | 138.18 | Not a tie |

## 3. Catalogue

### 3.1 Plans (8)

Rentals are before GST. Domestic voice is unlimited on every plan (A-74).

| Code | Rental | Data included | Data beyond plan | SMS | ISD voice |
|---|---|---|---|---|---|
| `PP_299` | ₹299.00 | 30 GB | ₹0.02/MB | 3,000/month, then ₹1.00 each | ₹6.00/min |
| `PP_399` | ₹399.00 | 40 GB | ₹0.02/MB | same | ₹6.00/min |
| `PP_499` | ₹499.00 | 75 GB | ₹0.02/MB | same | ₹6.00/min |
| `PP_599` | ₹599.00 | 100 GB | ₹0.02/MB | same | ₹6.00/min |
| `PP_699` | ₹699.00 | 150 GB | ₹0.02/MB | same | ₹6.00/min |
| `PP_999` | ₹999.00 | 300 GB | ₹0.02/MB | same | 100 min included, then ₹6.00/min |
| `PP_1199` | ₹1,199.00 | Unlimited | — | same | 200 min included, then ₹6.00/min |
| `PP_1499` | ₹1,499.00 | Unlimited | — | same | 500 min included, then ₹6.00/min |

- 1 GB = 1,024 MB. The overage rate is per MB, so every charge is exact in paise (1 GB
  over = ₹20.48).
- **International roaming, pay per use** (the same on every plan, A-75). Band `GCC`
  (includes AE): data ₹2.00/MB, voice ₹60.00/min (incoming and outgoing), SMS ₹25.00
  each. Band `WORLD`: data ₹5.00/MB, voice ₹120.00/min, SMS ₹25.00 each. No plan
  includes roaming, so a plan change is never cheaper than a pack for a one-off trip.

### 3.2 Add-ons (5)

| Code | Price (before GST) | Contents | Validity |
|---|---|---|---|
| `DATA_10GB` | ₹150.00 | 10 GB domestic data | Until the end of the bill cycle |
| `DATA_25GB` | ₹325.00 | 25 GB domestic data | Until the end of the bill cycle |
| `IR_GCC_7D` | ₹899.00 | GCC roaming: 1 GB data, 100 min voice (in + out), 20 SMS | 7 days |
| `IR_GCC_30D` | ₹2,199.00 | GCC roaming: 5 GB, 300 min, 100 SMS | 30 days |
| `IR_WORLD_10D` | ₹2,999.00 | WORLD roaming: 2 GB, 100 min, 50 SMS | 10 days |

`IR_GCC_30D` and `IR_WORLD_10D` cost more than what scenario 1 actually incurred. They
test that the simulator never recommends a pack that costs more than the charges it
would replace.

## 4. Customers

MSISDNs are synthetic, in the `+91 5xxxx xxxxx` range, which is outside India's mobile
number series 6–9. They cannot belong to a real subscriber (A-76). They are masked
everywhere except the BSS gateway call (security.md).

| Account | Scenario | Plan | Cycle day | GST state | Supply | Data use per month (GB, Mar→Sep) |
|---|---|---|---|---|---|---|
| 1001 | 1. UAE roaming, no pack | `PP_699` | 1 | 27 Maharashtra | Intra (CGST+SGST) | 112, 118, 110, 121, 115, 119, **114** + 550 MB roaming |
| 1002 | 2. Domestic data overage | `PP_399` | 6 | 29 Karnataka | Inter (IGST) | 31, 33, 35, 36, 38, **43**, **58** |
| 1003 | 3. Third-party VAS, no opt-in | `PP_499` | 11 | 27 Maharashtra | Intra | 55, 58, 61, 57, 63, 60, 62 |
| 1004 | 4. Mid-cycle upgrade proration | `PP_399` → `PP_699` on 1 Sep | 16 | 07 Delhi | Inter | 36, 37, 38, 36, 39, 37, 39 |
| 1005 | 5. Duplicate line item | `PP_599` | 21 | 27 Maharashtra | Intra | 86, 90, 88, 93, 91, 95, 94 |
| 1006 | 6. Normal bill | `PP_499` | 1 | 33 Tamil Nadu | Inter | 62, 65, 68, 64, 66, 70, 67 |

Data volumes were chosen so that **no cheaper plan fits** any customer except where the
scenario needs one (customer 1002). This stops the simulator from reporting a "saving"
that would count as an invented issue. For example, 1006 at 67 GB on `PP_399` would cost
₹951.96 before ISD (₹975.96 with its 4 ISD minutes), against ₹499.00 (₹523.00) on its
current plan. *(Corrected in 4a: this example said ₹952.96; 27 GB × 1,024 MB × ₹0.02 =
₹552.96, plus ₹399.00 = ₹951.96. It was an illustration, not a test value.)* **Exception
found in 4a:** 1004's September usage (39 GB) fits `PP_399`. That period mixes two plans, so
the simulator does not rank it (Q-27, deterministic-core.md §3.2). Domestic voice and SMS are within
allowance every month: fixed plausible values, listed in the seed SQL, with no charge.

Ids (A-77): `bill_id = account_id × 10000 + YYMM` (for example `10012609`);
`line_item_id = bill_id × 100 + sequence`.

## 5. Bills

"Baseline" = the mean of the 3 bills before the current one (June to August), rounded
HALF_EVEN, the same as `diffBills(currentPeriod, baselineMonths=3)`. "Excess" = current
minus baseline. The engine's final definitions are made in 4a; these are the values the
seed must support.

### 5.1 Customer 1001: UAE roaming without a pack (intra-state)

| Months | Line items (before GST) | Taxable | CGST 9% | SGST 9% | Total |
|---|---|---|---|---|---|
| Mar–Aug (each) | Rental `PP_699` 699.00 | 699.00 | 62.91 | 62.91 | **824.82** |
| **Sep** | Rental 699.00; ROAMING AE data 550 MB × ₹2.00 = 1,100.00; ROAMING AE voice 10 min × ₹60.00 = 600.00; ROAMING AE SMS 3 × ₹25.00 = 75.00 | 2,474.00 | 222.66 | 222.66 | **2,919.32** |

- Trip: 12–18 Aug 2026 (7 days), country `AE`, usage period August (cycle day 1).
- Excess: taxable +₹1,775.00; total +₹2,094.50 (+253.9%). **3.54x** the August bill (the
  SPEC's "about 3.5x").
- Expected recommendation: `IR_GCC_7D` (₹899.00) covers the whole trip: 550 MB ≤ 1 GB,
  10 min ≤ 100, 3 SMS ≤ 20. It would have saved ₹876.00 before GST, **₹1,033.68
  including GST**.
- Goodwill, for reference: 15% of the bill = ₹437.90, which is ≤ ₹500. At Level 1 any
  credit still needs the customer's confirmation (SPEC §4.5). The seed has no prior
  credits (A-78).

### 5.2 Customer 1002: domestic data overage (inter-state)

| Month | Line items | Taxable | IGST 18% | Total |
|---|---|---|---|---|
| Mar–Jul (each) | Rental `PP_399` 399.00 | 399.00 | 71.82 | **470.82** |
| Aug | Rental 399.00; DATA beyond plan 3,072 MB × ₹0.02 = 61.44 | 460.44 | 82.88 | **543.32** |
| **Sep** | Rental 399.00; DATA beyond plan 18,432 MB × ₹0.02 = 368.64 | 767.64 | 138.18 | **905.82** |

- Usage rises over months (43 GB in August, 58 GB in September against 40 GB included), so
  a plan change is justified by a trend, not by one month.
- Baseline: taxable ₹419.48 (it includes August's small overage), total ₹494.99. Excess:
  taxable +₹348.16, total +₹410.83 (+83.0%).
- Expected simulation for September's usage (58 GB). Savings are measured against the
  **re-rated current plan**, ₹767.64 (see §7):

| Option | Cost before GST | Saving | Bill total incl. IGST |
|---|---|---|---|
| **`PP_499`** (75 GB) | 499.00 | **268.64** | 588.82 |
| `PP_599` | 599.00 | 168.64 | 706.82 |
| `PP_699` | 699.00 | 68.64 | 824.82 |
| Add-on `DATA_10GB` + 8 GB overage | 712.84 | 54.80 | 841.15 |
| Add-on `DATA_25GB` | 724.00 | 43.64 | 854.32 |
| `PP_299` (worse) | 872.44 | −104.80 | 1,029.48 |

  The top 3 plans are `PP_499`, `PP_599` and `PP_699`; the best add-on is `DATA_10GB`.
  Decision Q-21: plans ranked top 3; the best single add-on returned separately; no
  multiples in v1. Implemented in 4a (deterministic-core.md §3.5).

### 5.3 Customer 1003: third-party VAS without double opt-in (intra-state)

| Month | Line items | Taxable | CGST 9% | SGST 9% | Total |
|---|---|---|---|---|---|
| Mar–Aug (each) | Rental `PP_499` 499.00; VAS "Cricket Scores" (monthly) 29.50 | 528.50 | 47.56 | 47.56 | **623.62** |
| **Sep** | Rental 499.00; VAS "Cricket Scores" 29.50; VAS "Astro Daily" weekly renewal × 4 (15 Aug, 22 Aug, 29 Aug, 05 Sep) at 49.00 = 196.00 | 724.50 | 65.20 | 65.20 | **854.90** |

- Subscription fixtures (TMF622 mock, §6):
  - **"Cricket Scores"** (third party "Demo VAS Provider 1"): activated 2026-02-10 by SMS,
    **double opt-in complete** (consent 09:14 IST, confirmation reply 09:16 IST). A
    legitimate VAS: the agent must **not** refund it.
  - **"Astro Daily"** ("Demo VAS Provider 2"): activated 2026-08-15 by a WAP click. There
    is a first-consent record, and the **confirmation is missing**.
- Excess: taxable +₹196.00, total +₹231.28 (+37.1%).
- Expected: unsubscribe "Astro Daily", a **full refund of ₹196.00 before GST (₹231.28
  including GST, decision Q-20)**, and offer third-party barring. Each weekly charge is its own
  line item, so the refund cites 4 line-item ids.
- Rounding: both months hit a HALF_EVEN tie (§2).

### 5.4 Customer 1004: mid-cycle plan upgrade (inter-state)

| Month | Line items | Taxable | IGST 18% | Total |
|---|---|---|---|---|
| Mar–Aug (each) | Rental `PP_399` 399.00 | 399.00 | 71.82 | **470.82** |
| **Sep** | PRORATION `PP_399` 16–31 Aug (16/31 days) = 205.94; PRORATION `PP_699` 1–15 Sep (15/31 days) = 338.23 | 544.17 | 97.95 | **642.12** |

- Usage period 16 Aug – 15 Sep = 31 days. Plan change order 2026-09-01, customer
  initiated through the app, completed (TMF622 fixture).
- Proration (A-79): rental × days on the plan ÷ days in the usage period, rounded
  HALF_EVEN per line. 399 × 16/31 = 205.9354… → **205.94**; 699 × 15/31 = 338.2258… →
  **338.23**.
- Excess: taxable +₹145.17, total +₹171.30 (+36.4%). This is a material change, but a
  **legitimate** one: the expected outcome is an explanation only, with no action.
- The October bill (a full `PP_699` rental) is not seeded.

### 5.5 Customer 1005: duplicate line item (intra-state)

| Month | Line items | Taxable | CGST 9% | SGST 9% | Total |
|---|---|---|---|---|---|
| Mar–Aug (each) | Rental `PP_599` 599.00 | 599.00 | 53.91 | 53.91 | **706.82** |
| **Sep** | Rental `PP_599` 599.00; Rental `PP_599` 599.00 (**same service period, same `external_ref`**) | 1,198.00 | 107.82 | 107.82 | **1,413.64** |

- Exactly 2.0x. The duplicate is recognisable in the data: same category, amount,
  service period and `external_ref`.
- Expected: `raiseDispute` citing the duplicate line-item id; amount ₹599.00 before GST,
  ₹706.82 including GST. No goodwill credit for the same amount (PRD US-RES-03).
- Of the 6 scenarios, this one causes the trap in §7: compared with the **billed**
  ₹1,198.00, every plan would look like a saving.

### 5.6 Customer 1006: normal bill (inter-state)

ISD calls at ₹6.00/min give small, natural variation.

| Month | ISD min | Taxable | IGST 18% | Total |
|---|---|---|---|---|
| Mar | 2 | 511.00 | 91.98 | 602.98 |
| Apr | 3 | 517.00 | 93.06 | 610.06 |
| May | 1 | 505.00 | 90.90 | 595.90 |
| Jun | 4 | 523.00 | 94.14 | 617.14 |
| Jul | 2 | 511.00 | 91.98 | 602.98 |
| Aug | 3 | 517.00 | 93.06 | 610.06 |
| **Sep** | 4 | 523.00 | 94.14 | **617.14** |

- Excess: taxable +₹6.00, total +₹7.08 (**+1.2%**). September equals June, the highest of
  the 6 prior months, so the current bill is not a new maximum.
- `PP_499` is the cheapest plan for 67 GB, so the simulator finds **no saving**.
  Expected: "your bill is normal", with no cause flagged and no action (NFR-08).

### 5.7 Two thresholds: "meaningful increase" in chat vs proactive anomaly

These are **two different rules with different jobs**. They must not be merged or share
config keys (A-83):

| | Chat: "meaningful increase" (decision Q-22) | Proactive: `AnomalyDetector` |
|---|---|---|
| Question it answers | When a customer asks, is the change worth explaining as a cause, or should we say "your bill is normal"? | Should we contact the customer **unprompted** about this bill? |
| Rule | Excess ≥ **₹100** **and** ≥ **10%** of the baseline | Ratio ≥ **1.8x** the baseline **or** z-score **> 2.5** |
| Built in | `BillDiffEngine` (4a) | `AnomalyDetector` (4b per SPEC §11; see Q-26) |
| Config | `billshock.analysis.chat.min-excess-inr=100`, `…min-excess-pct=10` | `billshock.anomaly.ratio-threshold=1.8`, `…z-threshold=2.5`, `…z-history-bills=6` |
| Cost of a false positive | A cause is explained that the customer may not care about | An unwanted message and a proactive LLM diagnosis (capacity plan: 4% of bills flagged, SPEC §1.4) |

Definitions (A-83):
- Both rules use **GST-inclusive bill totals**, which is what the customer sees.
- Chat baseline, and the proactive ratio's baseline: the mean of the **3** bills before
  the current one, rounded HALF_EVEN (the `diffBills` baseline).
- z-score: over the **6** bills before the current one, using the sample standard deviation
  (n − 1): z = (current − mean₆) ÷ σ₆.
- **Flat history** (decision Q-24): when σ₆ is below a small floor (config
  `billshock.anomaly.flat-sigma-floor-inr=1.00`), the history is treated as flat, the z
  rule does not apply, and only the ratio rule decides. 4 of the 6 seed accounts are flat
  (σ₆ = 0.00).

**Expected results: test expectations** (decision Q-26: the chat column is tested in 4a,
the proactive columns in 4b, where `AnomalyDetector` is built):

| Account | Scenario | Current total | 3-bill baseline | Excess (₹, incl. GST) | Excess % | **Flagged in chat** | Ratio | 6-bill mean | σ₆ | z | **Proactive: numeric anomaly** | **Proactive: rule-based (4b, Q-25)** |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1001 | Roaming | 2,919.32 | 824.82 | +2,094.50 | +253.9% | **Yes** | 3.539 | 824.82 | 0.00 | n/a (flat) | **Yes** (ratio) | — |
| 1002 | Data overage | 905.82 | 494.99 | +410.83 | +83.0% | **Yes** | 1.830 | 482.90 | 29.60 | 14.29 | **Yes** (ratio and z) | — |
| 1003 | VAS, no opt-in | 854.90 | 623.62 | +231.28 | +37.1% | **Yes** | 1.371 | 623.62 | 0.00 | n/a (flat) | **No** | **Anomaly alert**: new third-party VAS without double opt-in |
| 1004 | Proration | 642.12 | 470.82 | +171.30 | +36.4% | **Yes** (explained as legitimate) | 1.364 | 470.82 | 0.00 | n/a (flat) | **No** | **Informational notice** (not an anomaly): first bill after a plan change, explains proration (optional rule) |
| 1005 | Duplicate | 1,413.64 | 706.82 | +706.82 | +100.0% | **Yes** | 2.000 | 706.82 | 0.00 | n/a (flat) | **Yes** (ratio) | — |
| 1006 | Normal | 617.14 | 610.06 | +7.08 | +1.2% | **No** (fails both ₹100 and 10%) | 1.012 | 606.52 | 7.43 | 1.43 | **No** | — |

Notes:
- **1002 is flagged by the ratio with a margin of only 0.03 (1.830 against 1.8).** The z rule
  also flags it (14.29), so the result is robust, but a change to 1002's seed amounts must
  re-check this row.
- **1003** passes neither numeric rule (+37% on a flat history). The dedicated
  `AnomalyDetector` rule "new third-party VAS charge without a double opt-in record"
  (Q-25, 4b) catches it regardless of amount. It needs opt-in evidence from BSS at
  screening time (R-15).
- **1004** gets an **informational notification**, not an anomaly alert, from the
  optional 4b rule "first bill after a plan change". It explains the proration and does
  not start an LLM diagnosis as an anomaly (Q-25).
- Rule outcomes are separate from the numeric outcome. A bill can fire both, and the
  `rulesFired[]` field of `bill.anomaly.detected` lists which ones fired.

**Size of the seed:** 42 bills and 130 line items (67 charge lines + 63 TAX lines: 3 intra-state accounts × 7 bills × 2, plus 3 inter-state accounts × 7 × 1).

## 6. Data held by the mock gateways (JSON fixtures, not tables)

Following data-architecture.md §1, subscriptions, add-on activations, VAS opt-in evidence
and orders are **not stored** locally. The in-process mock gateways serve them from JSON
fixtures under `src/main/resources/bss-fixtures/` (A-80):

| Gateway (TMF) | Fixture content |
|---|---|
| TMF622 Product Ordering / inventory | Active plan per account with its start date; account 1004's plan change order (2026-09-01, `PP_399` → `PP_699`, app, completed); account 1003's two VAS subscriptions with activation date, channel and opt-in evidence (first consent, confirmation, or explicitly **missing**); third-party barring status (off for everyone) |
| TMF620 Product Catalog | The same 8 plans and 5 add-ons as the local catalogue tables |
| TMF635 Usage (detail on demand) | Per-session detail for 1001's roaming trip and 1002's September data, consistent with the aggregates |
| TMF678 Customer Bill | Accepts adjustment/credit requests; records them in memory (for 6a) |
| TMF621 Trouble Ticket | Accepts disputes and escalations; returns a ticket id; records them in memory |

## 7. Design points found while building the seed (handed to 4a)

1. **Simulator savings are measured against the re-rated current plan, not the billed
   total.** Otherwise a billing error (1005) or a VAS charge (1003) shows up as a plan
   "saving" on every plan. For 1005, the re-rated current plan is ₹599.00, so there is no
   saving; the ₹599.00 difference belongs to the dispute.
2. **Diagnosis amounts and GST** (decision Q-23). Each cause carries three amounts,
   all computed in Java:
   - `amountExclGst`: the line-item delta, before GST
   - `gstAmount`: the §2 rule applied to that delta (CGST + SGST, or IGST, each rounded
     HALF_EVEN)
   - `amountInclGst`: the sum of the two

   If the per-cause GST amounts do not add up exactly to the actual tax delta on the bill,
   the difference goes to the largest cause. Σ `amountInclGst` = total excess **exactly**
   (PRD US-DIA-01). For every seed scenario that difference is ₹0.00:

   | Account | Cause | Excl. GST | GST | **Incl. GST** (what the reply names) |
   |---|---|---|---|---|
   | 1001 | Roaming (AE) | 1,775.00 | 319.50 (CGST 159.75 + SGST 159.75) | **2,094.50** |
   | 1002 | Data beyond plan | 348.16 | 62.67 (IGST) | **410.83** |
   | 1003 | VAS "Astro Daily" (no opt-in) | 196.00 | 35.28 (CGST 17.64 + SGST 17.64) | **231.28** |
   | 1004 | Proration (plan change) | 145.17 | 26.13 (IGST) | **171.30** |
   | 1005 | Duplicate rental | 599.00 | 107.82 (CGST 53.91 + SGST 53.91) | **706.82** |
   | 1006 | — (normal) | — | — | — |

   **Reply rule (decision Q-23):** every amount in a reply, template or notification says
   whether it includes GST. When the reply names a cause, it gives the GST-inclusive figure
   first, because that matches the increase in the bill total the customer sees, for
   example "roaming in the UAE added ₹2,094.50 incl. GST (₹1,775.00 excl. GST plus
   ₹319.50 GST)". Tool results provide pre-formatted, labelled strings, which the model
   copies verbatim. The grounding check verifies that each amount and label came from a
   tool result, regenerates once on a label mismatch, then falls back to the template
   (llm-architecture.md §7, §10).
3. The customer 1004 simulation straddles two plans within one period. **Decided (Q-27):**
   a period with a mid-cycle plan change mixes usage across two plans, so it is not
   representative for re-rating. `simulatePlans` returns `RECENT_PLAN_CHANGE` with the
   change date and no ranking. The rule expires after one full bill cycle on the new plan;
   after that the period is simulated normally (deterministic-core.md §3.2).

## 8. Proposed schema changes (edited in place in V1–V6, answer 3)

Compared with data-architecture.md §4.1. If approved, data-architecture.md is updated
with them.

| Table | Change | Why |
|---|---|---|
| `account` | Add `gst_state_code char(2) NOT NULL` | Place of supply (§2) |
| `bill` | Add `place_of_supply char(2)`, `supply_type` (`INTRA` \| `INTER`) | A snapshot on the bill, because the account's state can change later |
| `bill_line_item` | Add `tax_component` (`CGST` \| `SGST` \| `IGST`; only on TAX lines, CHECK) and `tax_rate numeric(5,2)` | Tax lines carry their component and rate |
| `bill_line_item` | **Remove `tax_amount`** | Tax is computed at bill level (§2), not per line |
| `bill_line_item` | Add `service_period_start`, `service_period_end` (nullable) | Proration (1004) and duplicate detection (1005) need the service period of a charge |
| Config | `billshock.tax.supplier-state-codes` (list), `billshock.tax.gst-rate=18.00` | §2 |

## 9. Seed tests (in `./mvnw verify`, Phase 3a)

- Each account has exactly 7 bills, March to September 2026.
- Every bill: `subtotal` = Σ non-tax lines; `tax_total` = Σ TAX lines; `total` =
  `subtotal + tax_total`; the TAX lines match the §2 rule for the account's supply type.
  This is checked by recomputing GST in the test.
- Every total in §5 matches exactly (parameterised test).
- Σ daily rows = the period row, for the August and September billed periods.
- Usage charges on period rows equal the matching line items.
- An insert with `bill_period = 2028-01-01` fails (no partition).
- Actual row sizes are measured with `pg_column_size` on the seed and compared with A-54
  and A-26. The result goes in PROGRESS.md.

**Daily rows (generation rule, A-81):** each monthly quantity is split evenly across the
days of the usage period in whole units, with the remainder on the last day. Account
1001's roaming falls only on 12–18 Aug. Overage charges are attributed in date order,
starting from the day the included allowance runs out. ISD minutes (`isd_min`, Q-28, added
in 4a) and their charges are placed on the last day of the period. *(As built:
`V1003__seed_usage.sql`.)*

## 10. Assumptions introduced (A-71 to A-83; also in assumptions.md §8)

| ID | Assumption |
|---|---|
| A-71 | The seed uses 5 bill-cycle days: 1, 6, 11, 16 and 21 |
| A-72 | GST: tax on the bill-level taxable value; CGST 9% + SGST 9% intra-state, IGST 18% inter-state; each component rounded HALF_EVEN to 2 dp; place of supply = the billing-address state for postpaid mobile. **FOR TAX REVIEW** (real BSS rounding may differ; we never recompute issued bills) |
| A-73 | Supplier-registered states are a config list; the seed uses `[27]` (Maharashtra) only, to keep IGST covered. Large Indian telcos typically register in every state they serve, so most real postpaid bills would be intra-state. **FOR TAX REVIEW** |
| A-74 | All seed plans have unlimited domestic voice; SMS 3,000/month; data overage ₹0.02/MB; ISD ₹6.00/min |
| A-75 | Pay-per-use roaming rates are the same on every plan; one voice rate for incoming and outgoing calls |
| A-76 | Seed MSISDNs are in the `+91 5…` range, outside India's 6–9 mobile series |
| A-77 | Seed id scheme: `bill_id = account_id × 10000 + YYMM`; `line_item_id = bill_id × 100 + seq` |
| A-78 | No seed account has a prior goodwill credit, so the "no credit in 6 months" rule passes |
| A-79 | Proration = rental × days on the plan ÷ days in the usage period, HALF_EVEN per line, billed in the period of use |
| A-80 | Subscriptions, opt-in evidence and orders for the mocks come from JSON fixtures, not tables |
| A-81 | Daily seed rows follow the even-split rule in §9 |
| A-82 | Phase 3a set-up: Flyway runs at startup only in the `dev` and `test` profiles (a pre-deploy Job elsewhere, Phase 11); seed migrations live in `db/seed`, which only those profiles enable; 3a imports the Spring AI BOM only (starters come in 5a); `./mvnw verify` needs Docker (Testcontainers) |
| A-83 | Threshold definitions (§5.7): both rules on GST-inclusive totals; chat and ratio baseline = mean of 3 prior bills; z over 6 prior bills with sample σ; ratio ≥ 1.8, z > 2.5 (strict); chat excess ≥ ₹100 and ≥ 10% |

## 11. Decisions and open questions

**Decided (owner, 2026-09-25):**

| # | Decision |
|---|---|
| Q-20 | Credits and refunds **include GST**, and the goodwill thresholds (15% of the bill, ₹500, ₹2,000) apply to the **GST-inclusive** amount. `ProposedAction` stores the amount before GST plus the computed GST component; the mock TMF678 issues the credit including GST. **FOR TAX REVIEW** (A-72) |
| Q-21 | `simulatePlans` ranks plans (top 3) and returns the best single add-on separately; no multiples of an add-on in v1 |
| Q-22 | Chat "meaningful increase" = excess ≥ ₹100 **and** ≥ 10% of the baseline. It is **separate** from the proactive `AnomalyDetector` rule (≥ 1.8x or z > 2.5); see §5.7 |
| Q-23 | Causes are carried before GST with an explicit GST component, so they add up to the total excess exactly. **Replies always say whether an amount includes GST, and name a cause by its GST-inclusive figure** (§7, point 2) |
| — | Tool-call cap (llm-architecture.md §9): `escalateToHuman` and `recordDiagnosis` are excluded from the 8, and **each is allowed at most once per turn** |
| Q-24 | Flat history: when σ₆ is below a configurable floor (₹1.00), the z rule does not apply and only the ratio decides |
| Q-25 | 4b adds the `AnomalyDetector` rule "new third-party VAS charge without a double opt-in record" (an anomaly alert), and an optional rule "first bill after a plan change" (an informational notification explaining proration, not an anomaly alert) |
| Q-26 | `AnomalyDetector` stays in 4b. 4a tests the chat column of §5.7; 4b tests the proactive columns |
| — | GST labels: tools return pre-formatted, GST-labelled strings; the model copies them verbatim; grounding verifies amount + label; label mismatch → regenerate once → template (replaces the stricter "reject any unqualified amount" gate) |
