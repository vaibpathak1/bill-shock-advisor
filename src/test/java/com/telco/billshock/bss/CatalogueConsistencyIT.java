package com.telco.billshock.bss;

import com.telco.billshock.bss.ProductCatalogGateway.CatalogAddOn;
import com.telco.billshock.bss.ProductCatalogGateway.CatalogPlan;
import com.telco.billshock.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mock TMF620 fixture and the seeded catalogue replica must not drift apart
 * (seed-scenarios.md §6).
 */
@IntegrationTest
class CatalogueConsistencyIT {

    @Autowired
    ProductCatalogGateway catalog;

    @Autowired
    JdbcClient jdbc;

    @Test
    void plansMatchTheReplica() {
        assertThat(catalog.plans()).extracting(p -> p.code() + "|" + p.name() + "|" + p.monthlyRental())
            .containsExactlyInAnyOrderElementsOf(jdbc.sql("SELECT code || '|' || name || '|' || monthly_rental FROM plan")
                .query(String.class).list());
        assertThat(catalog.plans()).extracting(CatalogPlan::code).hasSize(8);
    }

    @Test
    void addOnsMatchTheReplica() {
        assertThat(catalog.addOns()).extracting(a -> a.code() + "|" + a.name() + "|" + a.price())
            .containsExactlyInAnyOrderElementsOf(jdbc.sql("SELECT code || '|' || name || '|' || price FROM add_on")
                .query(String.class).list());
        assertThat(catalog.addOns()).extracting(CatalogAddOn::code).hasSize(5);
    }

    @Test
    void everyPlanHasTheSameTenTariffRows() {
        assertThat(jdbc.sql("SELECT count(*) FROM tariff_rate GROUP BY plan_id").query(Long.class).list())
            .hasSize(8).containsOnly(10L);
    }
}
