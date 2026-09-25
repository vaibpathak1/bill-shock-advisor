package com.telco.billshock.bss;

import com.telco.billshock.bss.CatalogReadModel.Band;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Roaming band per country (A-90): the catalogue tables price roaming by band, and the
 * usage replica records roaming by country. For example {@code GCC: [AE, SA, ...]}.
 */
@Validated
@ConfigurationProperties("billshock.catalog")
public record RoamingBandProperties(Map<Band, Set<String>> roamingBands) {

    public RoamingBandProperties {
        roamingBands = roamingBands == null ? Map.of() : Map.copyOf(roamingBands);
        Map<String, Band> seen = new HashMap<>();
        roamingBands.forEach((band, countries) -> countries.forEach(country -> {
            Band previous = seen.put(country, band);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Country " + country + " is configured in two roaming bands: " + previous + " and " + band);
            }
        }));
    }
}
