package com.telco.billshock.agent;

import com.telco.billshock.agent.ToolCallBudget.Admission;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** llm-architecture.md §9; deterministic-core.md §5 (Q-29). */
class ToolCallBudgetTest {

    private final ToolCallBudget budget = new ToolCallBudgetProperties(8,
            Set.of("escalateToHuman", "recordDiagnosis")).newTurn();

    @Test
    void allowsEightCountedCallsAndStopsTheNinth() {
        for (int i = 0; i < 8; i++) {
            assertThat(budget.admit(i % 2 == 0 ? "getBillSummary" : "getLineItems")).isEqualTo(Admission.ALLOWED);
        }

        assertThat(budget.admit("simulatePlans")).isEqualTo(Admission.LIMIT_REACHED);
        assertThat(budget.admit("simulatePlans")).isEqualTo(Admission.LIMIT_REACHED);
        assertThat(budget.countedCalls()).isEqualTo(8);
    }

    @Test
    void onceOnlyToolsDoNotCountAndRunOnceEach() {
        assertThat(budget.admit("recordDiagnosis")).isEqualTo(Admission.ALLOWED);
        assertThat(budget.admit("recordDiagnosis")).isEqualTo(Admission.ALREADY_CALLED_THIS_TURN);
        assertThat(budget.admit("escalateToHuman")).isEqualTo(Admission.ALLOWED);
        assertThat(budget.admit("escalateToHuman")).isEqualTo(Admission.ALREADY_CALLED_THIS_TURN);
        assertThat(budget.countedCalls()).isZero();
    }

    @Test
    void theAgentCanStillEscalateAfterTheLimit() {
        for (int i = 0; i < 8; i++) {
            budget.admit("getLineItems");
        }
        assertThat(budget.admit("getLineItems")).isEqualTo(Admission.LIMIT_REACHED);

        assertThat(budget.admit("escalateToHuman")).isEqualTo(Admission.ALLOWED);
        assertThat(budget.admit("recordDiagnosis")).isEqualTo(Admission.ALLOWED);
    }

    @Test
    void eachTurnStartsFresh() {
        ToolCallBudgetProperties properties = new ToolCallBudgetProperties(1, Set.of("escalateToHuman"));
        ToolCallBudget first = properties.newTurn();
        first.admit("diffBills");
        first.admit("escalateToHuman");

        ToolCallBudget second = properties.newTurn();
        assertThat(second.admit("diffBills")).isEqualTo(Admission.ALLOWED);
        assertThat(second.admit("escalateToHuman")).isEqualTo(Admission.ALLOWED);
    }

    @Test
    void rejectsAnEmptyBudget() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolCallBudget(0, Set.of()));
    }
}
