package com.telco.billshock.actions.internal;

import java.util.List;

import org.springframework.stereotype.Component;

import com.telco.billshock.actions.ActionEffect;
import com.telco.billshock.actions.ActionStatus;
import com.telco.billshock.actions.ActionType;
import com.telco.billshock.bss.TroubleTicketGateway;
import com.telco.billshock.bss.TroubleTicketGateway.TicketRequest;
import com.telco.billshock.bss.TroubleTicketGateway.TicketType;
import com.telco.billshock.domain.InrFormat;

/**
 * TMF621 tickets: a confirmed billing dispute, and the hand-off ticket of every {@code ESCALATED}
 * action, opened without customer confirmation (actions.md §6.5, owner answer 2).
 */
@Component
class TicketExecutor implements ActionExecutor {

    private final TroubleTicketGateway tickets;

    TicketExecutor(TroubleTicketGateway tickets) {
        this.tickets = tickets;
    }

    @Override
    public boolean supports(ActionType type) {
        return type == ActionType.DISPUTE;
    }

    @Override
    public List<Step> steps(ActionRow a) {
        return List.of(new Step(ActionEffect.DISPUTE, () -> tickets.createTicket(new TicketRequest(a.account(),
                TicketType.BILLING_DISPUTE, BssCalls.period(a.params().billPeriod()), a.params().lineItemIds(),
                "Billing dispute: " + a.params().reason(), a.bssKey()))
            .ticketId()));
    }

    /** The hand-off ticket of an escalated action of any type. */
    List<Step> escalation(ActionRow a) {
        if (a.status() != ActionStatus.ESCALATED) {
            throw new IllegalArgumentException("Not escalated: " + a.actionId());
        }
        return List.of(new Step(ActionEffect.ESCALATION, () -> tickets.createTicket(new TicketRequest(a.account(),
                TicketType.ESCALATION, BssCalls.period(a.params().billPeriod()),
                a.params().lineItemIds() == null ? List.of() : a.params().lineItemIds(), summary(a), a.bssKey()))
            .ticketId()));
    }

    /** Masked text only (security.md): the model's summary is PII-scrubbed before it is stored. */
    private static String summary(ActionRow a) {
        if (a.type() == ActionType.ESCALATION) {
            return "Customer hand-off. Summary: " + a.params().summary() + " Reason: " + a.params().reason();
        }
        return "Needs a human decision: " + a.type() + (a.amountInclGst() == null ? ""
                : " of " + InrFormat.inclGst(a.amountInclGst())) + " (above the self-service limit)";
    }
}
