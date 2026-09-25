/**
 * ProposedAction workflow, guardrail chain, executors, idempotency and autonomy flags
 * (ADR-004; Phase 4a guardrails, 6a workflow). Depends on {@code analysis} for the duplicate-charge
 * rule used by the goodwill guardrail (deterministic-core.md §1).
 */
@ApplicationModule(allowedDependencies = { "domain", "bss", "analysis", "audit", "security" })
package com.telco.billshock.actions;

import org.springframework.modulith.ApplicationModule;
