package com.telco.billshock.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import com.telco.billshock.agent.internal.DiagnosisTool;
import com.telco.billshock.domain.AccountId;

/**
 * SPEC §4.3 rule 2: no tool parameter can name an account, customer or MSISDN. Checked over
 * every {@code @Tool} method, so a new tool cannot slip past it.
 */
class ToolIdentityRuleTest {

    private static final List<String> FORBIDDEN = List.of("account", "customer", "msisdn", "mobile", "phone", "user",
            "subscriber");

    private static Stream<Method> toolMethods() {
        return Stream.of(BillingTools.class, UsageTools.class, CatalogTools.class, DiagnosisTool.class)
            .flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
            .filter(m -> m.isAnnotationPresent(Tool.class));
    }

    @Test
    void theNineToolsAreFound() {
        assertThat(toolMethods().map(m -> m.getAnnotation(Tool.class).name())).containsExactlyInAnyOrder(
                "getBillSummary", "getBillHistory", "diffBills", "getLineItems", "getUsageDetails",
                "getActiveSubscriptions", "searchPlanCatalog", "simulatePlans", "recordDiagnosis");
    }

    @Test
    void noToolParameterCarriesIdentity() {
        toolMethods().forEach(m -> {
            for (Parameter p : m.getParameters()) {
                if (p.getType() == ToolContext.class) {
                    continue;
                }
                String name = p.getName().toLowerCase(Locale.ROOT);
                assertThat(FORBIDDEN).as(m.getName() + "(" + p.getName() + ")").noneMatch(name::contains);
                assertThat(p.getType()).as(m.getName() + "(" + p.getName() + ")").isNotEqualTo(AccountId.class);
            }
        });
    }

    @Test
    void everyToolHasADescriptionWithoutDigitsOrRupees() {
        // No data, dates or thresholds in the cached prefix (llm-architecture.md §5); small counts are words or 1-6.
        toolMethods().forEach(m -> {
            String description = m.getAnnotation(Tool.class).description();
            assertThat(description).as(m.getName()).isNotBlank().doesNotContain("₹").doesNotContainPattern("\\d{2,}");
        });
    }
}
