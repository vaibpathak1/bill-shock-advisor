package com.telco.billshock.agent.internal;

import static com.telco.billshock.support.SeedScenarioFixtures.SEPTEMBER;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.telco.billshock.agent.internal.DiagnosisTool.ActionInput;
import com.telco.billshock.agent.internal.DiagnosisTool.CauseInput;
import com.telco.billshock.agent.internal.TurnState.LlmDiagnosis;
import com.telco.billshock.analysis.AnalysisProperties;
import com.telco.billshock.analysis.BillDiff;
import com.telco.billshock.analysis.BillDiffEngine;
import com.telco.billshock.analysis.PlanSimulation;
import com.telco.billshock.analysis.PlanSimulator;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillShockDiagnosis;
import com.telco.billshock.domain.BillShockDiagnosis.ActionType;
import com.telco.billshock.domain.Money;
import com.telco.billshock.support.SeedScenarioFixtures;

/** The diagnosis gate: money always from the engine (agent.md §8.2). */
class DiagnosisGateTest {

    private static final Set<String> CODES = Set.of("PP_499", "PP_599", "DATA_10GB", "IR_GCC_7D");

    private final AnalysisProperties properties = new AnalysisProperties(
            new AnalysisProperties.Chat(new BigDecimal("100.00"), BigDecimal.TEN), new AnalysisProperties.Simulator(3));
    private final SeedScenarioFixtures.InMemoryBilling billing = SeedScenarioFixtures.billing();
    private final BillDiff diff1002 = new BillDiffEngine(billing, SeedScenarioFixtures.GST, properties)
        .diff(AccountId.of(1002), SEPTEMBER)
        .orElseThrow();
    private final Optional<PlanSimulation> sim1002 = new PlanSimulator(billing, SeedScenarioFixtures.catalog(),
            SeedScenarioFixtures.inventory(), SeedScenarioFixtures.GST, properties)
        .simulate(AccountId.of(1002), SEPTEMBER);

    private GroundingGate gate() {
        GroundingGate gate = new GroundingGate("BSA-REF-TEST");
        gate.allow("₹317.00 incl. GST ₹588.82 incl. GST");
        return gate;
    }

    private static LlmDiagnosis llm(String amount, String total, List<ActionInput> actions) {
        return new LlmDiagnosis(List.of(new CauseInput("DATA", amount)), total, "MEDIUM", actions);
    }

    @Test
    void acceptsTheModelsConfidenceAndActionsWhenTheMoneyMatches() {
        DiagnosisGate.Outcome outcome = DiagnosisGate.evaluate(diff1002, sim1002,
                llm("₹410.83 incl. GST", "₹410.83 incl. GST",
                        List.of(new ActionInput("PLAN_CHANGE", "PP_499", "PP_499: new bill ₹588.82 incl. GST, saving ₹317.00 incl. GST."))),
                CODES, gate());

        assertThat(outcome.violation()).isFalse();
        BillShockDiagnosis d = outcome.diagnosis();
        assertThat(d.source()).isEqualTo(BillShockDiagnosis.Source.LLM);
        assertThat(d.confidence()).isEqualTo(BillShockDiagnosis.Confidence.MEDIUM);
        assertThat(d.totalExcess()).isEqualTo(Money.of("410.83"));
        assertThat(d.causes()).singleElement().satisfies(c -> assertThat(c.amountInclGst()).isEqualTo(Money.of("410.83")));
        assertThat(d.recommendedActions()).singleElement().satisfies(a -> assertThat(a.code()).isEqualTo("PP_499"));
    }

    @Test
    void anyMismatchFallsBackToTheEngineBuiltDiagnosis() {
        List<LlmDiagnosis> wrong = List.of(
                llm("₹410.00 incl. GST", "₹410.83 incl. GST", List.of()), // cause amount
                llm("₹410.83 excl. GST", "₹410.83 incl. GST", List.of()), // label
                llm("₹410.83 incl. GST", "₹400.00 incl. GST", List.of()), // total
                new LlmDiagnosis(List.of(new CauseInput("ROAMING", "₹410.83 incl. GST")), "₹410.83 incl. GST", "HIGH",
                        List.of()), // group
                new LlmDiagnosis(List.of(), "₹410.83 incl. GST", "HIGH", List.of()), // missing cause
                new LlmDiagnosis(List.of(new CauseInput("DATA", "₹410.83 incl. GST")), "₹410.83 incl. GST", "SURE",
                        List.of()), // confidence
                llm("₹410.83 incl. GST", "₹410.83 incl. GST", List.of(new ActionInput("PLAN_CHANGE", "PP_9999", "x"))),
                llm("₹410.83 incl. GST", "₹410.83 incl. GST", List.of(new ActionInput("REFUND_EVERYTHING", null, "x"))),
                llm("₹410.83 incl. GST", "₹410.83 incl. GST",
                        List.of(new ActionInput("PLAN_CHANGE", "PP_499", "Save ₹999.00 incl. GST."))),
                llm("₹410.83 incl. GST", "₹410.83 incl. GST",
                        List.of(new ActionInput("NO_ACTION", null, "I have credited your account."))));
        for (LlmDiagnosis input : wrong) {
            DiagnosisGate.Outcome outcome = DiagnosisGate.evaluate(diff1002, sim1002, input, CODES, gate());
            assertThat(outcome.violation()).as(input.toString()).isTrue();
            assertThat(outcome.diagnosis().source()).isEqualTo(BillShockDiagnosis.Source.ENGINE);
        }
    }

    @Test
    void theEngineBuiltDiagnosisRecommendsFromTheSimulator() {
        BillShockDiagnosis d = DiagnosisGate.evaluate(diff1002, sim1002, null, CODES, gate()).diagnosis();

        assertThat(d.source()).isEqualTo(BillShockDiagnosis.Source.ENGINE);
        assertThat(d.recommendedActions()).extracting(BillShockDiagnosis.RecommendedAction::type)
            .containsExactly(ActionType.PLAN_CHANGE, ActionType.ADD_ON);
        assertThat(d.recommendedActions().getFirst().summary())
            .isEqualTo("Switch to PP_499: new bill ₹588.82 incl. GST, saving ₹317.00 incl. GST.");
    }
}
