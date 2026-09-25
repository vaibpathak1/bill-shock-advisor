/**
 * ProposedAction workflow, guardrail chain, executors, idempotency and autonomy flags
 * (ADR-004; Phase 4a guardrails, 6a workflow).
 */
@ApplicationModule(allowedDependencies = { "domain", "bss", "audit", "security" })
package com.telco.billshock.actions;

import org.springframework.modulith.ApplicationModule;
