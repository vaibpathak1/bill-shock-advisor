package com.telco.billshock.analysis;

import com.telco.billshock.bss.CatalogReadModel.AddOn;
import com.telco.billshock.bss.CatalogReadModel.Band;
import com.telco.billshock.bss.CatalogReadModel.Plan;
import com.telco.billshock.bss.CatalogReadModel.TariffRate;
import com.telco.billshock.bss.CatalogReadModel.UsageType;
import com.telco.billshock.domain.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

/**
 * Re-rates one period's usage on a plan, optionally with one add-on
 * (deterministic-core.md §3.4). Each usage type and band is charged as
 * {@code round(max(0, usage − included − add-on allowance) × unit price)}, HALF_EVEN.
 */
final class UsageRater {

    private UsageRater() {
    }

    /** Domestic and ISD usage, plus roaming by band. */
    record PeriodUsage(BigDecimal dataMb, BigDecimal voiceMin, BigDecimal smsCount, BigDecimal isdMin,
            Map<Band, BandUsage> roaming) {

        PeriodUsage {
            roaming = Map.copyOf(roaming);
        }
    }

    /** Roaming in one band; the span runs from the first to the last roaming day, inclusive. */
    record BandUsage(BigDecimal dataMb, BigDecimal voiceMin, BigDecimal smsCount, LocalDate firstDay,
            LocalDate lastDay) {

        long spanDays() {
            return ChronoUnit.DAYS.between(firstDay, lastDay) + 1;
        }
    }

    /** @param needsDailyUsage a day-based tariff was used, so this cost is not reliable */
    record Rating(Money cost, boolean needsDailyUsage) {
    }

    /**
     * @param addOn {@code null} for the plan alone; a roaming pack must already be known to apply
     * @return empty if the plan has no tariff for a usage type that was used
     */
    static Optional<Rating> rate(Plan plan, AddOn addOn, PeriodUsage usage) {
        Accumulator acc = new Accumulator(plan.monthlyRental().plus(addOn == null ? Money.ZERO : addOn.price()));
        boolean domesticAddOn = addOn != null && !addOn.isRoamingPack();
        acc.charge(plan, UsageType.DATA_MB, Band.DOMESTIC, usage.dataMb(), domesticAddOn ? addOn.dataMb() : null);
        acc.charge(plan, UsageType.VOICE_MIN, Band.DOMESTIC, usage.voiceMin(),
                domesticAddOn ? addOn.voiceMin() : null);
        acc.charge(plan, UsageType.SMS, Band.DOMESTIC, usage.smsCount(), domesticAddOn ? sms(addOn) : null);
        acc.charge(plan, UsageType.ISD_MIN, Band.INTL, usage.isdMin(), null);
        usage.roaming().forEach((band, roaming) -> {
            boolean pack = addOn != null && addOn.isRoamingPack() && addOn.countryGroup() == band;
            acc.charge(plan, UsageType.ROAM_DATA_MB, band, roaming.dataMb(), pack ? addOn.dataMb() : null);
            acc.charge(plan, UsageType.ROAM_VOICE_MIN, band, roaming.voiceMin(), pack ? addOn.voiceMin() : null);
            acc.charge(plan, UsageType.ROAM_SMS, band, roaming.smsCount(), pack ? sms(addOn) : null);
        });
        return acc.ratable ? Optional.of(new Rating(acc.cost, acc.needsDailyUsage)) : Optional.empty();
    }

    /** A roaming pack applies to a trip in its band that fits its validity (no multiples, Q-21). */
    static boolean packApplies(AddOn addOn, PeriodUsage usage) {
        BandUsage roaming = usage.roaming().get(addOn.countryGroup());
        return roaming != null && addOn.validityDays() != null && roaming.spanDays() <= addOn.validityDays();
    }

    private static BigDecimal sms(AddOn addOn) {
        return addOn.smsCount() == null ? null : BigDecimal.valueOf(addOn.smsCount());
    }

    private static final class Accumulator {

        private Money cost;
        private boolean ratable = true;
        private boolean needsDailyUsage;

        Accumulator(Money fixed) {
            this.cost = fixed;
        }

        void charge(Plan plan, UsageType type, Band band, BigDecimal used, BigDecimal addOnAllowance) {
            if (used == null || used.signum() == 0) {
                return;
            }
            Optional<TariffRate> tariff = plan.tariff(type, band);
            if (tariff.isEmpty()) {
                ratable = false;
                return;
            }
            TariffRate rate = tariff.get();
            needsDailyUsage |= rate.dayBased();
            if (rate.unlimited()) {
                return;
            }
            BigDecimal allowance = rate.includedUnits().add(addOnAllowance == null ? BigDecimal.ZERO : addOnAllowance);
            BigDecimal over = used.subtract(allowance).max(BigDecimal.ZERO);
            cost = cost.plus(Money.rounded(over.multiply(rate.unitPrice())));
        }
    }
}
