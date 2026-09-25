/**
 * {@code @Tool} classes (Phase 5a/6a). Tools take the customer from the SecurityContext,
 * never from LLM arguments (SPEC §4.3 rule 2).
 */
@ApplicationModule(allowedDependencies = { "domain", "analysis", "bss", "actions", "security", "audit" })
package com.telco.billshock.tools;

import org.springframework.modulith.ApplicationModule;
