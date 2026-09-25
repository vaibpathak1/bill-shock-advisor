package com.telco.billshock.bss.internal.mock;

import com.telco.billshock.bss.CustomerBillGateway;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mock TMF678 adjustments: records requests in memory. A repeated idempotency key returns
 * the first receipt and never credits twice (SPEC §4.3 rule 4).
 */
@Component
@Profile("mock-bss")
class MockCustomerBillGateway implements CustomerBillGateway {

    private final List<AdjustmentRequest> applied = new CopyOnWriteArrayList<>();
    private final Map<String, AdjustmentReceipt> receiptsByKey = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public AdjustmentReceipt requestAdjustment(AdjustmentRequest request) {
        return receiptsByKey.computeIfAbsent(request.idempotencyKey(), key -> {
            applied.add(request);
            return new AdjustmentReceipt("MOCK-ADJ-" + sequence.incrementAndGet(), request.accountId(),
                    request.amountExclGst().plus(request.gstAmount()));
        });
    }

    /** Adjustments applied so far, one per idempotency key. */
    List<AdjustmentRequest> appliedAdjustments() {
        return List.copyOf(applied);
    }
}
