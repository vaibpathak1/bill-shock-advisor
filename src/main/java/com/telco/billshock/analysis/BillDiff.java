package com.telco.billshock.analysis;

import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.Money;
import com.telco.billshock.domain.SupplyType;

import java.math.BigDecimal;
import java.util.List;

/**
 * The result of {@link BillDiffEngine#diff}: the current bill against the mean of the
 * baseline bills (deterministic-core.md §2). All amounts are computed in Java; the LLM only
 * explains them (ADR-003).
 *
 * <p>For {@link Verdict#INSUFFICIENT_HISTORY} the baseline and excess fields are
 * {@code null}, and there are no causes or group deltas.
 *
 * @param baselineBillCount how many bills the baseline was computed from
 * @param totalExcess current total − baseline total, GST-inclusive; the authoritative excess
 * @param subtotalExcess current subtotal − baseline subtotal, before GST
 * @param excessPercent total excess as a percentage of the baseline total, 1 dp (reported only)
 * @param ratio current total ÷ baseline total, 3 dp (reported only)
 * @param groupDeltas every group present on the compared bills, whatever the verdict
 * @param causes only for {@link Verdict#MEANINGFUL_INCREASE}; Σ {@code amountInclGst} = {@code totalExcess}
 * @param roundingAdjustmentExclGst added to the largest cause so that the causes add up to the subtotal excess
 * @param roundingAdjustmentGst added to the largest cause so that the causes add up to the total excess
 * @param findings evaluated on every bill, whatever the verdict
 */
public record BillDiff(AccountId accountId, BillPeriod billPeriod, SupplyType supplyType, Money currentSubtotal,
        Money currentTotal, int baselineBillCount, Money baselineSubtotal, Money baselineTotal, Money subtotalExcess,
        Money totalExcess, BigDecimal excessPercent, BigDecimal ratio, Verdict verdict, List<GroupDelta> groupDeltas,
        List<Cause> causes, Money roundingAdjustmentExclGst, Money roundingAdjustmentGst, List<Finding> findings) {

    public BillDiff {
        groupDeltas = List.copyOf(groupDeltas);
        causes = List.copyOf(causes);
        findings = List.copyOf(findings);
    }

    public enum Verdict {

        /** The chat rule holds (Q-22): the change is explained cause by cause. */
        MEANINGFUL_INCREASE,

        /** The bill is normal; no cause is presented (NFR-08, US-DIA-02). */
        NORMAL,

        /** No earlier bill to compare with. */
        INSUFFICIENT_HISTORY
    }

    /** One group, before GST, unreconciled. */
    public record GroupDelta(CauseGroup group, Money current, Money baseline, Money delta) {
    }

    /**
     * One cause of the excess (Q-23): before GST, its GST, and the GST-inclusive figure by
     * which a reply names it.
     *
     * @param lineItemIds the current bill's lines in this group
     */
    public record Cause(CauseGroup group, Money amountExclGst, Money gstAmount, Money amountInclGst,
            List<Long> lineItemIds) {

        public Cause {
            lineItemIds = List.copyOf(lineItemIds);
        }
    }

    public enum FindingType {

        /** The same charge twice on one bill (scenario 5 → dispute, US-RES-03). */
        DUPLICATE_CHARGE,

        /** A charge from a subscription that is on none of the baseline bills (scenario 3). */
        NEW_SUBSCRIPTION_CHARGE,

        /** Proration lines: the plan changed during the period (scenario 4). */
        PLAN_CHANGE_PRORATION
    }

    /**
     * @param lineItemIds the lines concerned; for a duplicate, the original first
     * @param subscriptionId for {@link FindingType#NEW_SUBSCRIPTION_CHARGE}, otherwise {@code null}
     */
    public record Finding(FindingType type, List<Long> lineItemIds, String subscriptionId) {

        public Finding {
            lineItemIds = List.copyOf(lineItemIds);
        }
    }
}
