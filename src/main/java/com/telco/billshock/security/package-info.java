/**
 * Security configuration, {@code CurrentCustomer} and PII masking (Phase 5a).
 */
@ApplicationModule(allowedDependencies = { "domain" })
package com.telco.billshock.security;

import org.springframework.modulith.ApplicationModule;
