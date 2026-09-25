/**
 * Append-only audit log (SPEC §4.3 rule 5; Phase 6a). The table is immutable at the
 * database level (V5 trigger).
 */
@ApplicationModule(allowedDependencies = { "domain" })
package com.telco.billshock.audit;

import org.springframework.modulith.ApplicationModule;
