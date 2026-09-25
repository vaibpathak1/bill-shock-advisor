package com.telco.billshock.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The GST rule of an Indian telecom invoice (A-72, FOR TAX REVIEW): tax is computed once
 * on the bill-level taxable value. Intra-state supply is split into CGST and SGST at half
 * the rate each; inter-state supply is IGST at the full rate. Each component is rounded
 * HALF_EVEN to 2 decimal places on its own, so CGST + SGST can differ from IGST by one
 * paisa (seed-scenarios.md §2).
 *
 * <p>Issued bills come from BSS and are never recomputed. This rule is used for ingest
 * consistency checks, plan simulations and the GST part of refunds and credits (Q-20).
 */
public final class GstCalculator {

    private final BigDecimal ratePercent;

    /**
     * @param ratePercent the full GST rate, for example {@code 18.00}
     */
    public GstCalculator(BigDecimal ratePercent) {
        Objects.requireNonNull(ratePercent, "ratePercent");
        if (ratePercent.signum() < 0) {
            throw new IllegalArgumentException("GST rate must not be negative: " + ratePercent);
        }
        this.ratePercent = ratePercent;
    }

    public GstBreakdown compute(Money taxable, SupplyType supplyType) {
        return switch (supplyType) {
            case INTRA -> {
                BigDecimal half = ratePercent.divide(BigDecimal.TWO);
                yield new GstBreakdown(List.of(
                        new TaxLine(TaxComponent.CGST, half, taxable.percent(half)),
                        new TaxLine(TaxComponent.SGST, half, taxable.percent(half))));
            }
            case INTER -> new GstBreakdown(List.of(
                    new TaxLine(TaxComponent.IGST, ratePercent, taxable.percent(ratePercent))));
        };
    }

    public enum TaxComponent {
        CGST, SGST, IGST
    }

    /** One tax component as it appears on the bill. */
    public record TaxLine(TaxComponent component, BigDecimal ratePercent, Money amount) {
    }

    /** The tax lines for one taxable value. */
    public record GstBreakdown(List<TaxLine> lines) {

        public GstBreakdown {
            lines = List.copyOf(lines);
        }

        public Money total() {
            return lines.stream().map(TaxLine::amount).reduce(Money.ZERO, Money::plus);
        }
    }
}
