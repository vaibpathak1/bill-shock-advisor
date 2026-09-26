package com.telco.billshock.actions.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

import com.telco.billshock.actions.ActionEffect;
import com.telco.billshock.actions.ActionStatus;
import com.telco.billshock.actions.ActionType;
import com.telco.billshock.actions.ActionView;
import com.telco.billshock.actions.ExecutionStep.StepStatus;
import com.telco.billshock.domain.InrFormat;

/**
 * Turns a row into the customer's {@link ActionView}: a summary per type and a message per
 * status, both server templates (actions.md §3.3, §4, §12). The same wording reaches the model
 * through the conversation digest, so the model describes an action exactly as the server does.
 */
final class ActionViews {

    static final String AMOUNT_CHANGED = "AMOUNT_CHANGED";
    static final String GUARDRAIL_REJECTED = "GUARDRAIL_REJECTED";

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private ActionViews() {
    }

    static ActionView view(ActionRow a) {
        boolean withAmount = a.amount() != null;
        boolean supervisorAhead = a.requiresSupervisor()
                && (a.status() == ActionStatus.PENDING_CONFIRMATION || a.status() == ActionStatus.AWAITING_SUPERVISOR);
        return new ActionView(a.actionId(), a.type(), a.status(), summary(a),
                withAmount ? new ActionView.AmountView(a.amountInclGst().amount().toPlainString(),
                        InrFormat.inclGst(a.amountInclGst())) : null,
                withAmount ? InrFormat.exclGst(a.amount()) : null, withAmount ? InrFormat.gst(a.gstAmount()) : null,
                supervisorAhead, a.conversationId(), time(a.createdAt()),
                a.status() == ActionStatus.PENDING_CONFIRMATION ? time(a.expiresAt()) : null, time(a.decidedAt()),
                time(a.executedAt()), a.externalRef(),
                a.execution().stream().map(s -> new ActionView.StepView(s.effect(), s.status(), s.reference())).toList(),
                message(a));
    }

    static String summary(ActionRow a) {
        ActionParams p = a.params();
        return switch (a.type()) {
            case GOODWILL_CREDIT -> "Goodwill credit on the " + charges(p.categories()) + " of your " + month(p.billPeriod())
                    + " bill";
            case VAS_UNSUBSCRIBE -> "Unsubscribe from " + name(p.productName())
                    + (Boolean.TRUE.equals(p.requestRefund()) ? " and refund its charges" : "");
            case THIRD_PARTY_BARRING -> "Block third-party (VAS) charges on your number";
            case PLAN_CHANGE -> "Change your plan to " + p.planCode()
                    + ("NEXT_CYCLE".equals(p.effective()) ? " from your next bill cycle" : " now");
            case ADD_ON -> "Add the " + p.addOnCode() + " pack";
            case DISPUTE -> "Dispute " + count(p.lineItemIds()) + " on your " + month(p.billPeriod()) + " bill";
            case ESCALATION -> "Hand over to a customer care specialist";
        };
    }

    static String message(ActionRow a) {
        return switch (a.status()) {
            case PENDING_CONFIRMATION -> a.requiresSupervisor()
                    ? "Waiting for your confirmation. After you confirm, a supervisor reviews it before it is applied."
                    : "Waiting for your confirmation. Nothing has changed yet.";
            case AWAITING_SUPERVISOR -> "Confirmed. A supervisor reviews it before it is applied; nothing has changed yet.";
            case APPROVED, AUTO_APPROVED, EXECUTING ->
                "We are checking with the billing system. Nothing more is needed from you.";
            case EXECUTED -> "Done.";
            case FAILED -> failed(a);
            case ESCALATED -> a.stepDone(ActionEffect.ESCALATION)
                    ? "Handed to a customer care specialist (reference " + a.externalRef() + ")."
                    : "A customer care specialist will follow up.";
            case REJECTED -> switch (a.failureReason() == null ? "" : a.failureReason()) {
                case AMOUNT_CHANGED -> "The amount has changed since this was proposed. Nothing was changed on your"
                        + " account. Ask for a fresh proposal.";
                case GUARDRAIL_REJECTED -> "This can no longer be done as proposed. Nothing was changed on your account.";
                default -> "Rejected. Nothing was changed on your account.";
            };
            case EXPIRED -> "This proposal expired without confirmation. Nothing was changed. Ask for a fresh proposal"
                    + " if you still want it.";
        };
    }

    /** Partial VAS execution (owner, 6a design review): say what was done and what was not. */
    private static String failed(ActionRow a) {
        boolean unsubscribed = a.stepDone(ActionEffect.UNSUBSCRIBE);
        boolean refundRejected = a.execution()
            .stream()
            .anyMatch(s -> s.effect() == ActionEffect.REFUND && s.status() == StepStatus.REJECTED);
        if (a.type() == ActionType.VAS_UNSUBSCRIBE && unsubscribed && refundRejected) {
            return "The subscription is cancelled. The refund could not be processed automatically; our billing team"
                    + " will handle it manually.";
        }
        return "The billing system could not complete this. Nothing was changed on your account.";
    }

    private static String charges(List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return "charges";
        }
        return String.join(" and ", categories.stream().map(c -> c.toLowerCase(Locale.ROOT)).toList()) + " charges";
    }

    private static String month(String yyyyMm) {
        return yyyyMm == null ? "latest" : YearMonth.parse(yyyyMm).format(MONTH);
    }

    private static String count(List<Long> ids) {
        int n = ids == null ? 0 : ids.size();
        return n == 1 ? "1 charge" : n + " charges";
    }

    /** TMF622 product names are third-party text: control characters removed, length capped. */
    static String name(String untrusted) {
        if (untrusted == null || untrusted.isBlank()) {
            return "this service";
        }
        String clean = untrusted.replaceAll("\\p{Cntrl}", " ").strip();
        return clean.length() > 60 ? clean.substring(0, 60) : clean;
    }

    private static String time(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i.truncatedTo(ChronoUnit.SECONDS), INDIA).toString();
    }
}
