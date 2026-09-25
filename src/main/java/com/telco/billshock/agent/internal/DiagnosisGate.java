package com.telco.billshock.agent.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.telco.billshock.agent.internal.DiagnosisTool.ActionInput;
import com.telco.billshock.agent.internal.DiagnosisTool.CauseInput;
import com.telco.billshock.agent.internal.TurnState.LlmDiagnosis;
import com.telco.billshock.analysis.BillDiff;
import com.telco.billshock.analysis.BillDiff.FindingType;
import com.telco.billshock.analysis.BillDiff.Verdict;
import com.telco.billshock.analysis.PlanSimulation;
import com.telco.billshock.domain.BillShockDiagnosis;
import com.telco.billshock.domain.BillShockDiagnosis.ActionType;
import com.telco.billshock.domain.BillShockDiagnosis.Cause;
import com.telco.billshock.domain.BillShockDiagnosis.Confidence;
import com.telco.billshock.domain.BillShockDiagnosis.RecommendedAction;
import com.telco.billshock.domain.BillShockDiagnosis.Source;
import com.telco.billshock.domain.InrFormat;

/**
 * Checks the model's {@code recordDiagnosis} input against the engine (llm-architecture.md §7
 * "diagnosis gate"; agent.md §8.2). The stored diagnosis always carries the engine's money; the
 * model may contribute only the confidence and the recommended actions, and only when its
 * causes and total match the engine exactly. Otherwise the engine-built diagnosis is used.
 */
final class DiagnosisGate {

    static final int MAX_ACTIONS = 3;

    record Outcome(BillShockDiagnosis diagnosis, boolean violation) {
    }

    private DiagnosisGate() {
    }

    /**
     * @param llm the model's input, or {@code null} if {@code recordDiagnosis} was not called
     * @param catalogCodes plan and add-on codes valid today
     */
    static Outcome evaluate(BillDiff diff, Optional<PlanSimulation> simulation, LlmDiagnosis llm,
            Set<String> catalogCodes, GroundingGate grounding) {
        BillShockDiagnosis engine = engineBuilt(diff, simulation);
        if (llm == null) {
            return new Outcome(engine, false);
        }
        Optional<List<RecommendedAction>> actions = actions(llm.recommendedActions(), catalogCodes, grounding);
        Optional<Confidence> confidence = confidence(llm.confidence());
        boolean valid = causesMatch(diff, llm.causes()) && totalMatches(diff, llm.totalExcess())
                && actions.isPresent() && confidence.isPresent();
        if (!valid) {
            return new Outcome(engine, true);
        }
        return new Outcome(new BillShockDiagnosis(engine.billPeriod(), engine.verdict(), engine.totalExcess(),
                engine.causes(), confidence.get(), actions.get(), Source.LLM), false);
    }

    /** The diagnosis from the engines alone: used without, or instead of, the model's input. */
    static BillShockDiagnosis engineBuilt(BillDiff diff, Optional<PlanSimulation> simulation) {
        List<Cause> causes = diff.verdict() == Verdict.MEANINGFUL_INCREASE
                ? diff.causes().stream().map(c -> new Cause(c.group().name(), c.amountInclGst(), c.lineItemIds())).toList()
                : List.of();
        List<RecommendedAction> actions = new ArrayList<>();
        simulation.filter(s -> s.status() == PlanSimulation.Status.SIMULATED).ifPresent(s -> {
            if (!s.plans().isEmpty()) {
                PlanSimulation.PlanOption p = s.plans().getFirst();
                actions.add(new RecommendedAction(ActionType.PLAN_CHANGE, p.planCode(),
                        "Switch to " + p.planCode() + ": new bill " + InrFormat.inclGst(p.totalInclGst()) + ", saving "
                                + InrFormat.inclGst(p.savingInclGst()) + "."));
            }
            if (s.bestAddOn() != null) {
                PlanSimulation.AddOnOption a = s.bestAddOn();
                actions.add(new RecommendedAction(ActionType.ADD_ON, a.addOnCode(),
                        "Add " + a.addOnCode() + ": new bill " + InrFormat.inclGst(a.totalInclGst()) + ", saving "
                                + InrFormat.inclGst(a.savingInclGst()) + "."));
            }
        });
        if (diff.findings().stream().anyMatch(f -> f.type() == FindingType.DUPLICATE_CHARGE)) {
            actions.add(new RecommendedAction(ActionType.DISPUTE, null, "Dispute the repeated charge."));
        }
        return new BillShockDiagnosis(diff.billPeriod(), diff.verdict().name(), diff.totalExcess(), causes,
                Confidence.HIGH, actions.stream().limit(MAX_ACTIONS).toList(), Source.ENGINE);
    }

    private static boolean causesMatch(BillDiff diff, List<CauseInput> input) {
        List<BillDiff.Cause> expected = diff.verdict() == Verdict.MEANINGFUL_INCREASE ? diff.causes() : List.of();
        if (input.size() != expected.size()) {
            return false;
        }
        Set<String> expectedPairs = expected.stream()
            .map(c -> c.group().name() + "|" + InrFormat.inclGst(c.amountInclGst()))
            .collect(Collectors.toSet());
        Set<String> inputPairs = input.stream()
            .map(c -> normalize(c.group()) + "|" + (c.amountInclGst() == null ? "" : c.amountInclGst().strip()))
            .collect(Collectors.toSet());
        return expectedPairs.equals(inputPairs);
    }

    private static boolean totalMatches(BillDiff diff, String totalExcess) {
        if (diff.totalExcess() == null) {
            return totalExcess == null || totalExcess.isBlank();
        }
        return totalExcess != null && totalExcess.strip().equals(InrFormat.inclGst(diff.totalExcess()));
    }

    private static Optional<Confidence> confidence(String value) {
        try {
            return Optional.of(Confidence.valueOf(normalize(value)));
        }
        catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<List<RecommendedAction>> actions(List<ActionInput> input, Set<String> catalogCodes,
            GroundingGate grounding) {
        if (input.size() > MAX_ACTIONS) {
            return Optional.empty();
        }
        List<RecommendedAction> actions = new ArrayList<>();
        for (ActionInput a : input) {
            ActionType type;
            try {
                type = ActionType.valueOf(normalize(a.type()));
            }
            catch (IllegalArgumentException e) {
                return Optional.empty();
            }
            boolean needsCode = type == ActionType.PLAN_CHANGE || type == ActionType.ADD_ON;
            if (needsCode && (a.code() == null || !catalogCodes.contains(a.code().strip()))) {
                return Optional.empty();
            }
            String summary = a.summary() == null ? "" : a.summary().strip();
            GroundingGate.Result check = grounding.check(summary);
            if (check.verdict() != GroundingGate.Verdict.PASS || check.actionClaimRewritten()) {
                return Optional.empty();
            }
            actions.add(new RecommendedAction(type, needsCode ? a.code().strip() : null, summary));
        }
        return Optional.of(actions);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }
}
