/**
 * ChatClient beans, prompts, orchestration, grounding gates, memory and fallback
 * templates (Phase 5a).
 */
@ApplicationModule(allowedDependencies = { "domain", "tools", "analysis", "security", "audit" })
package com.telco.billshock.agent;

import org.springframework.modulith.ApplicationModule;
