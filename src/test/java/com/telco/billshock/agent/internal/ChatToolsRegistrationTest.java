package com.telco.billshock.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.telco.billshock.actions.GuardrailProperties;
import com.telco.billshock.tools.ActionTools;
import com.telco.billshock.tools.BillingTools;
import com.telco.billshock.tools.CatalogTools;
import com.telco.billshock.tools.ConversationActions;
import com.telco.billshock.tools.EscalationTool;
import com.telco.billshock.tools.UsageTools;

/** The tools offered to the model per autonomy level (ADR-004, A-109; actions.md §2.1). */
class ChatToolsRegistrationTest {

    private static List<String> toolNames(int autonomyLevel) {
        GuardrailProperties properties = new GuardrailProperties(autonomyLevel, new BigDecimal("2000.00"),
                new GuardrailProperties.Goodwill(new BigDecimal("500.00"), new BigDecimal("15"), 6), 6);
        ActionTools actionTools = new ActionTools(null);
        ConversationActions actions = new ConversationActions(null, actionTools, new EscalationTool(actionTools),
                properties, null);
        return new LlmConfiguration()
            .chatTools(new BillingTools(null, null), new UsageTools(null, null),
                    new CatalogTools(null, null, null, null, new StaticListableBeanFactory().getBeanProvider(Clock.class)),
                    new DiagnosisTool(), actions)
            .callbacks()
            .stream()
            .map(c -> c.getToolDefinition().name())
            .toList();
    }

    @Test
    void levelOneOffersEveryActionToolSortedByName() {
        List<String> names = toolNames(1);
        assertThat(names).isSorted().contains("proposeGoodwillCredit", "proposeVasUnsubscribe",
                "proposeThirdPartyBarring", "proposePlanChange", "proposeAddOn", "raiseDispute", "escalateToHuman")
            .hasSize(16);
    }

    @Test
    void levelZeroOffersOnlyTheReadOnlyToolsAndTheHandOff() {
        List<String> names = toolNames(0);
        assertThat(names).contains("escalateToHuman", "diffBills", "recordDiagnosis")
            .noneMatch(n -> n.startsWith("propose") || n.equals("raiseDispute"))
            .hasSize(10);
    }
}
