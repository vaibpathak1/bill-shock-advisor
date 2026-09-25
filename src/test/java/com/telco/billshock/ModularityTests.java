package com.telco.billshock;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails the build on illegal cross-module dependencies or cycles (SPEC §4.1). The allowed
 * dependencies are declared in each module's {@code package-info.java} (architecture.md §4).
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(BillShockAdvisorApplication.class);

    @Test
    void verifiesModuleStructure() {
        modules.verify();
    }

    @Test
    void detectsTheMvpSliceModules() {
        // plan-and-budget.md §2a: the modules the MVP slice needs.
        assertThat(modules.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
            .containsExactlyInAnyOrder("actions", "agent", "analysis", "api", "audit", "bss", "domain", "security",
                    "tools");
    }

    @Test
    void writesModuleDocumentation() {
        // Diagrams and module canvases under target/spring-modulith-docs.
        new Documenter(modules).writeDocumentation();
    }
}
