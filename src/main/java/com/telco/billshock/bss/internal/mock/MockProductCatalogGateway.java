package com.telco.billshock.bss.internal.mock;

import com.telco.billshock.bss.ProductCatalogGateway;
import com.telco.billshock.domain.Money;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

/** Mock TMF620 catalogue: the same 8 plans and 5 add-ons as the seeded catalogue tables. */
@Component
@Profile("mock-bss")
class MockProductCatalogGateway implements ProductCatalogGateway {

    private final List<CatalogPlan> plans;
    private final List<CatalogAddOn> addOns;

    MockProductCatalogGateway(JsonMapper jsonMapper) {
        CatalogFixture fixture = BssFixtures.load(jsonMapper, "catalog.json", CatalogFixture.class);
        this.plans = fixture.plans().stream()
            .map(p -> new CatalogPlan(p.code(), p.name(), Money.of(p.monthlyRental())))
            .toList();
        this.addOns = fixture.addOns().stream()
            .map(a -> new CatalogAddOn(a.code(), a.name(), Money.of(a.price())))
            .toList();
    }

    @Override
    public List<CatalogPlan> plans() {
        return plans;
    }

    @Override
    public List<CatalogAddOn> addOns() {
        return addOns;
    }

    record CatalogFixture(List<PlanFixture> plans, List<AddOnFixture> addOns) {
    }

    record PlanFixture(String code, String name, BigDecimal monthlyRental) {
    }

    record AddOnFixture(String code, String name, BigDecimal price) {
    }
}
