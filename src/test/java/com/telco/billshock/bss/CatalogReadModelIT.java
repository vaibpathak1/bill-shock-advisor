package com.telco.billshock.bss;

import com.telco.billshock.bss.CatalogReadModel.AddOn;
import com.telco.billshock.bss.CatalogReadModel.Band;
import com.telco.billshock.bss.CatalogReadModel.Plan;
import com.telco.billshock.bss.CatalogReadModel.UsageType;
import com.telco.billshock.bss.CatalogReadModel.Validity;
import com.telco.billshock.domain.Money;
import com.telco.billshock.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The catalogue replica of seed-scenarios.md §3, read through {@link CatalogReadModel}. */
@IntegrationTest
class CatalogReadModelIT {

    private static final LocalDate ON = LocalDate.of(2026, 9, 15);

    @Autowired
    CatalogReadModel catalog;

    @Test
    void readsTheEightPlansWithTheirTariffs() {
        List<Plan> plans = catalog.plans(ON);

        assertThat(plans).extracting(Plan::code)
            .containsExactly("PP_299", "PP_399", "PP_499", "PP_599", "PP_699", "PP_999", "PP_1199", "PP_1499");
        assertThat(plans).allSatisfy(p -> assertThat(p.tariffs()).hasSize(10));
        Plan pp399 = plans.get(1);
        assertThat(pp399.monthlyRental()).isEqualTo(Money.of("399.00"));
        assertThat(pp399.tariff(UsageType.DATA_MB, Band.DOMESTIC)).get().satisfies(t -> {
            assertThat(t.includedUnits()).isEqualByComparingTo("40960");
            assertThat(t.unitPrice()).isEqualByComparingTo("0.02");
            assertThat(t.dayBased()).isFalse();
        });
        assertThat(pp399.tariff(UsageType.VOICE_MIN, Band.DOMESTIC)).get().satisfies(t -> assertThat(t.unlimited()).isTrue());
        assertThat(plans.get(5).tariff(UsageType.ISD_MIN, Band.INTL)).get()
            .satisfies(t -> assertThat(t.includedUnits()).isEqualByComparingTo("100"));
        assertThat(plans.get(6).tariff(UsageType.DATA_MB, Band.DOMESTIC)).get()
            .satisfies(t -> assertThat(t.unlimited()).isTrue());
        assertThat(pp399.tariff(UsageType.ROAM_VOICE_MIN, Band.GCC)).get()
            .satisfies(t -> assertThat(t.unitPrice()).isEqualByComparingTo("60.00"));
    }

    @Test
    void readsTheFiveAddOns() {
        List<AddOn> addOns = catalog.addOns(ON);

        assertThat(addOns).extracting(AddOn::code)
            .containsExactly("DATA_10GB", "DATA_25GB", "IR_GCC_7D", "IR_GCC_30D", "IR_WORLD_10D");
        assertThat(addOns.get(2)).satisfies(a -> {
            assertThat(a.price()).isEqualTo(Money.of("899.00"));
            assertThat(a.dataMb()).isEqualByComparingTo("1024");
            assertThat(a.voiceMin()).isEqualByComparingTo("100");
            assertThat(a.smsCount()).isEqualTo(20);
            assertThat(a.validity()).isEqualTo(Validity.DAYS);
            assertThat(a.validityDays()).isEqualTo(7);
            assertThat(a.countryGroup()).isEqualTo(Band.GCC);
        });
        assertThat(addOns.getFirst().isRoamingPack()).isFalse();
    }

    @Test
    void plansNotYetValidAreExcluded() {
        assertThat(catalog.plans(LocalDate.of(2024, 12, 31))).isEmpty();
    }

    @Test
    void mapsRoamingCountriesToBands() {
        assertThat(catalog.roamingBand("AE")).contains(Band.GCC);
        assertThat(catalog.roamingBand("US")).contains(Band.WORLD);
        assertThat(catalog.roamingBand("ZZ")).isEmpty();
    }
}
