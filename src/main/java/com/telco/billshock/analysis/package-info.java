/**
 * Deterministic engines, no LLM (ADR-003): BillDiffEngine and PlanSimulator (Phase 4a),
 * AnomalyDetector (Phase 4b).
 */
@ApplicationModule(allowedDependencies = { "domain", "bss" })
package com.telco.billshock.analysis;

import org.springframework.modulith.ApplicationModule;
