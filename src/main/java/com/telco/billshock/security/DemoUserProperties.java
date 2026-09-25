package com.telco.billshock.security;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * In-memory demo customers for the MVP slice (plan-and-budget §2a; A-97). Enabled only in the
 * {@code dev} and {@code test} profiles; with it off, no user exists and every API call is
 * rejected. Keycloak replaces this in Phase 8.
 *
 * @param password one shared password, from the environment ({@code DEMO_USER_PASSWORD})
 * @param users username → account id
 */
@ConfigurationProperties("billshock.security.demo-users")
public record DemoUserProperties(boolean enabled, String password, Map<String, Long> users) {

    public DemoUserProperties {
        users = users == null ? Map.of() : Map.copyOf(users);
    }
}
