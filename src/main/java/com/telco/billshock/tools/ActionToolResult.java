package com.telco.billshock.tools;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What an action tool tells the model (actions.md §3.2). Amounts are GST-labelled display strings
 * the model may repeat; the message is fixed server wording. Guardrail reason codes and
 * thresholds are never included (SPEC §4.5).
 *
 * @param status {@code PROPOSED}, {@code ALREADY_PROPOSED}, {@code ESCALATED} or {@code NOT_POSSIBLE}
 * @param actionStatus the action's state, for example {@code PENDING_CONFIRMATION}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionToolResult(String status, Long actionId, String type, String actionStatus, String summary,
        Amount amount, Boolean needsCustomerConfirmation, Boolean needsSupervisorReview, String reference,
        String message) implements ToolResult {

    static ActionToolResult notPossible(String message) {
        return new ActionToolResult("NOT_POSSIBLE", null, null, null, null, null, null, null, null, message);
    }
}
