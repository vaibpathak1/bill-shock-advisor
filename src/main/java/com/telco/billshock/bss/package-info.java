/**
 * BSS integration: gateway interfaces modelled on TM Forum Open APIs (TMF678, TMF635,
 * TMF620, TMF622, TMF621), their in-process mock implementations (Adapter/Gateway pattern,
 * SPEC §4.2), and the local read models for bills and usage (data-architecture.md §2).
 * The published API is this package; {@code internal} is private to the module.
 */
@ApplicationModule(allowedDependencies = { "domain", "audit" })
package com.telco.billshock.bss;

import org.springframework.modulith.ApplicationModule;
