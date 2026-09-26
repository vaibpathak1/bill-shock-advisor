package com.telco.billshock.actions;

/** {@code proposed_action.action_type}: one per action tool (SPEC §4.4; actions.md §3). */
public enum ActionType {
    GOODWILL_CREDIT, VAS_UNSUBSCRIBE, THIRD_PARTY_BARRING, PLAN_CHANGE, ADD_ON, DISPUTE, ESCALATION;

    public static ActionType of(ActionRequest request) {
        return switch (request) {
            case ActionRequest.GoodwillCredit c -> GOODWILL_CREDIT;
            case ActionRequest.VasUnsubscribe v -> VAS_UNSUBSCRIBE;
            case ActionRequest.ThirdPartyBarring b -> THIRD_PARTY_BARRING;
            case ActionRequest.PlanChange p -> PLAN_CHANGE;
            case ActionRequest.AddOnPurchase a -> ADD_ON;
            case ActionRequest.Dispute d -> DISPUTE;
            case ActionRequest.Escalation e -> ESCALATION;
        };
    }
}
