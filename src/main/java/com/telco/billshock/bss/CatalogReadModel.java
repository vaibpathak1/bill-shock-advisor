package com.telco.billshock.bss;

import com.telco.billshock.domain.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read access to the local catalogue replica: plans with their tariffs, and add-ons
 * (data-architecture.md §4.2; TMF620 is the source). Used for re-rating by the plan
 * simulator (deterministic-core.md §3).
 */
public interface CatalogReadModel {

    /** Plans valid on the date ({@code valid_from ≤ date < valid_to}), ordered by rental. */
    List<Plan> plans(LocalDate onDate);

    /** Add-ons valid on the date, ordered by price. */
    List<AddOn> addOns(LocalDate onDate);

    /** The roaming band of a country (config, A-90); empty if the country is not configured. */
    Optional<Band> roamingBand(String countryCode);

    enum UsageType {
        DATA_MB, VOICE_MIN, SMS, ISD_MIN, ROAM_DATA_MB, ROAM_VOICE_MIN, ROAM_SMS
    }

    enum Band {
        DOMESTIC, INTL, GCC, WORLD
    }

    enum Validity {
        DAYS, BILL_CYCLE
    }

    /**
     * @param includedUnits {@code null} means unlimited
     * @param unitPrice a rate in ₹ per unit (4 decimals), not an amount
     * @param dayBased re-rating needs daily usage (A-55)
     */
    record TariffRate(UsageType usageType, Band band, BigDecimal includedUnits, BigDecimal unitPrice, String unit,
            boolean dayBased) {

        public boolean unlimited() {
            return includedUnits == null;
        }
    }

    /** @param monthlyRental before GST */
    record Plan(String code, String name, Money monthlyRental, List<TariffRate> tariffs) {

        public Plan {
            tariffs = List.copyOf(tariffs);
        }

        public Optional<TariffRate> tariff(UsageType usageType, Band band) {
            return tariffs.stream()
                .filter(t -> t.usageType() == usageType && t.band() == band)
                .findFirst();
        }
    }

    /**
     * @param price before GST
     * @param dataMb included data, or {@code null}
     * @param voiceMin included minutes (incoming and outgoing for roaming packs), or {@code null}
     * @param smsCount included SMS, or {@code null}
     * @param validityDays set when {@code validity} is {@link Validity#DAYS}
     * @param countryGroup the roaming band of a roaming pack; {@code null} for a domestic add-on
     */
    record AddOn(String code, String name, Money price, BigDecimal dataMb, BigDecimal voiceMin, Integer smsCount,
            Validity validity, Integer validityDays, Band countryGroup) {

        public AddOn {
            Objects.requireNonNull(validity, "validity");
        }

        public boolean isRoamingPack() {
            return countryGroup != null;
        }
    }
}
