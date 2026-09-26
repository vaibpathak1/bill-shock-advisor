package com.telco.billshock.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What {@code GET /api/v1/meta} may reveal (owner answer 6): the scripted-demo flag and the app
 * version, nothing else.
 *
 * @param scriptedModel true only in the scripted demo run ({@code spring-boot:test-run}, actions.md §8.2)
 * @param version the Maven project version, filled in at build time
 */
@ConfigurationProperties("billshock.meta")
public record MetaProperties(boolean scriptedModel, String version) {
}
