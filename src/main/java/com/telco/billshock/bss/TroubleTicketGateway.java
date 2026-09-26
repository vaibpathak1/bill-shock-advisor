package com.telco.billshock.bss;

import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;

import java.util.List;

/**
 * TMF621 Trouble Ticket: billing disputes and escalations to a human.
 */
public interface TroubleTicketGateway {

    /**
     * @throws BssRejectedException the BSS definitely did not apply the request
     * @throws BssUnavailableException the outcome is unknown (timeout, connection error); retry with the same key
     */
    TicketReceipt createTicket(TicketRequest request);

    enum TicketType {
        BILLING_DISPUTE, ESCALATION
    }

    /**
     * @param lineItemIds the disputed line items (scenario 5 cites the duplicate)
     * @param summary masked text only (security.md)
     */
    record TicketRequest(AccountId accountId, TicketType type, BillPeriod billPeriod,
            List<Long> lineItemIds, String summary, String idempotencyKey) {

        public TicketRequest {
            lineItemIds = List.copyOf(lineItemIds);
        }
    }

    record TicketReceipt(String ticketId, String status) {
    }
}
