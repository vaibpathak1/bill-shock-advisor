package com.telco.billshock.domain;

import java.util.List;
import java.util.Objects;

/**
 * The structured diagnosis of one bill (SPEC §4.6). Every amount is an engine value; the LLM
 * contributes only the confidence and the recommended actions (agent.md §8.2).
 *
 * @param verdict the {@code BillDiff} verdict name
 * @param totalExcess GST-inclusive; {@code null} when there is no history to compare with
 * @param causes largest first; empty unless the verdict is a meaningful increase
 */
public record BillShockDiagnosis(BillPeriod billPeriod, String verdict, Money totalExcess, List<Cause> causes,
        Confidence confidence, List<RecommendedAction> recommendedActions, Source source) {

    public BillShockDiagnosis {
        Objects.requireNonNull(billPeriod, "billPeriod");
        Objects.requireNonNull(verdict, "verdict");
        causes = List.copyOf(causes);
        recommendedActions = List.copyOf(recommendedActions);
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(source, "source");
    }

    /** @param group a {@code CauseGroup} name */
    public record Cause(String group, Money amountInclGst, List<Long> lineItemIds) {

        public Cause {
            lineItemIds = List.copyOf(lineItemIds);
        }
    }

    /** @param code a plan or add-on code for plan and add-on actions, otherwise {@code null} */
    public record RecommendedAction(ActionType type, String code, String summary) {
    }

    public enum ActionType {
        PLAN_CHANGE, ADD_ON, VAS_UNSUBSCRIBE, THIRD_PARTY_BARRING, DISPUTE, GOODWILL_CREDIT, NO_ACTION
    }

    public enum Confidence {
        HIGH, MEDIUM, LOW
    }

    /** Who wrote the diagnosis: the LLM (checked by the diagnosis gate) or the engine. */
    public enum Source {
        LLM, ENGINE
    }
}
