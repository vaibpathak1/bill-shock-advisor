package com.telco.billshock.bss;

import com.telco.billshock.domain.Money;

import java.util.List;

/**
 * TMF620 Product Catalog Management. The catalogue is replicated into the local
 * {@code plan}, {@code tariff_rate} and {@code add_on} tables (data-architecture.md §4.2);
 * this gateway is the source for that replica.
 */
public interface ProductCatalogGateway {

    List<CatalogPlan> plans();

    List<CatalogAddOn> addOns();

    /** @param monthlyRental before GST */
    record CatalogPlan(String code, String name, Money monthlyRental) {
    }

    /** @param price before GST */
    record CatalogAddOn(String code, String name, Money price) {
    }
}
