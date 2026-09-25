package com.telco.billshock.analysis;

import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.Money;
import com.telco.billshock.domain.SupplyType;

import java.time.LocalDate;
import java.util.List;

/**
 * The result of {@link PlanSimulator#simulate} (deterministic-core.md §3). Every cost is the
 * plan-dependent part of the bill only (rental, add-on and usage charges); VAS, adjustments
 * and duplicate lines are excluded, so savings are measured against the re-rated current
 * plan, not the billed total (seed-scenarios.md §7.1).
 *
 * @param current the re-rated current plan; set only when {@code status} is {@link Status#SIMULATED}
 * @param plans cheaper plans, best first, at most the configured number; empty = no plan saves money
 * @param bestAddOn the best single add-on on the current plan, or {@code null} if none saves money
 * @param recentPlanChange set when {@code status} is {@link Status#RECENT_PLAN_CHANGE}
 * @param notSimulatableReason set when {@code status} is {@link Status#NOT_SIMULATABLE}
 */
public record PlanSimulation(AccountId accountId, BillPeriod billPeriod, Status status, SupplyType supplyType,
        PlanCost current, List<PlanOption> plans, AddOnOption bestAddOn, PlanChange recentPlanChange,
        NotSimulatableReason notSimulatableReason) {

    public PlanSimulation {
        plans = List.copyOf(plans);
    }

    public enum Status {
        SIMULATED,

        /**
         * The billed usage period contains a plan change, so its usage is split across two
         * plans and is not representative for re-rating (Q-27). No ranking is given. The
         * next bill, the first full cycle on the new plan, is simulated normally.
         */
        RECENT_PLAN_CHANGE,

        NOT_SIMULATABLE
    }

    public enum NotSimulatableReason {

        /** No usage aggregate for the billed period. */
        NO_USAGE,

        /** The plan of the period is not in the catalogue on the period's last day. */
        CURRENT_PLAN_UNKNOWN,

        /** The current plan has no tariff for a usage type that was used. */
        CURRENT_PLAN_NOT_RATABLE,

        /** A day-based tariff needs daily usage from BSS (ADR-007); not built yet. */
        DAY_BASED_TARIFF,

        /** A roaming country without a configured band (A-90). */
        UNKNOWN_ROAMING_COUNTRY
    }

    /** @param costExclGst plan-dependent charges before GST */
    public record PlanCost(String planCode, String planName, Money costExclGst, Money totalInclGst) {
    }

    public record PlanOption(String planCode, String planName, Money monthlyRental, Money costExclGst,
            Money totalInclGst, Money savingExclGst, Money savingInclGst) {
    }

    /** @param costExclGst the current plan's plan-dependent charges with this add-on, including its price */
    public record AddOnOption(String addOnCode, String addOnName, Money price, Money costExclGst, Money totalInclGst,
            Money savingExclGst, Money savingInclGst) {
    }

    /** @param fromPlanCode {@code null} if the change is known only from proration lines */
    public record PlanChange(LocalDate effectiveDate, String fromPlanCode, String toPlanCode) {
    }
}
