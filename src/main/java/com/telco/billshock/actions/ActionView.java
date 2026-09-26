package com.telco.billshock.actions;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A proposed action as the customer sees it: the body of {@code GET /api/v1/actions} items and of
 * the confirm/reject responses (actions.md §4), and the source of the SSE {@code action} event and
 * the conversation digest. Amounts are GST-labelled display strings; the summary and message are
 * server templates, never model text. Guardrail reason codes are never included (SPEC §4.5).
 *
 * @param amount the GST-inclusive amount (credit, refund or disputed amount), or {@code null}
 * @param reference the BSS receipt or ticket reference(s), or {@code null}
 * @param message what the status means for the customer, in one or two sentences
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionView(long actionId, ActionType type, ActionStatus status, String summary, AmountView amount,
        String amountExclGst, String gst, boolean needsSupervisorReview, UUID conversationId, String createdAt,
        String expiresAt, String decidedAt, String executedAt, String reference, List<StepView> execution,
        String message) {

    public ActionView {
        execution = execution == null ? List.of() : List.copyOf(execution);
    }

    /** @param display for example {@code ₹231.28 incl. GST} */
    public record AmountView(String value, String display) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepView(ActionEffect effect, ExecutionStep.StepStatus status, String reference) {
    }
}
