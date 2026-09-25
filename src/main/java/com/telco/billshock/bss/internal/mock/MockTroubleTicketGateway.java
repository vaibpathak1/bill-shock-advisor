package com.telco.billshock.bss.internal.mock;

import com.telco.billshock.bss.TroubleTicketGateway;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Mock TMF621: records disputes and escalations in memory, idempotent per key. */
@Component
@Profile("mock-bss")
class MockTroubleTicketGateway implements TroubleTicketGateway {

    private final List<TicketRequest> created = new CopyOnWriteArrayList<>();
    private final Map<String, TicketReceipt> receiptsByKey = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public TicketReceipt createTicket(TicketRequest request) {
        return receiptsByKey.computeIfAbsent(request.idempotencyKey(), key -> {
            created.add(request);
            return new TicketReceipt("MOCK-TT-" + sequence.incrementAndGet(), "ACKNOWLEDGED");
        });
    }

    /** Tickets created so far, one per idempotency key. */
    List<TicketRequest> createdTickets() {
        return List.copyOf(created);
    }
}
