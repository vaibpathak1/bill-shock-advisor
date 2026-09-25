package com.telco.billshock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Bill Shock Advisor: one Spring Modulith application, deployed as {@code chat-api} and
 * {@code proactive-worker} by Spring profile (ADR-001, A-59). Modules are the direct
 * sub-packages of this package (SPEC §4.1, architecture.md §4).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BillShockAdvisorApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillShockAdvisorApplication.class, args);
    }
}
