package com.telco.billshock.bss;

import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.Money;

/**
 * TMF678 Customer Bill Management: bill adjustments (goodwill credits, refunds).
 * Adapter/Gateway pattern (SPEC §4.2): a mock in v1, the real BSS adapter later.
 */
public interface CustomerBillGateway {

    /**
     * @throws BssRejectedException the BSS definitely did not apply the request
     * @throws BssUnavailableException the outcome is unknown (timeout, connection error); retry with the same key
     */
    AdjustmentReceipt requestAdjustment(AdjustmentRequest request);

    enum AdjustmentType {
        GOODWILL_CREDIT, REFUND
    }

    /**
     * @param amountExclGst amount before GST
     * @param gstAmount GST on the amount; credits and refunds include GST (Q-20)
     * @param idempotencyKey repeated requests with the same key must not credit twice
     */
    record AdjustmentRequest(AccountId accountId, BillPeriod billPeriod, AdjustmentType type,
            Money amountExclGst, Money gstAmount, String reason, String idempotencyKey) {
    }

    record AdjustmentReceipt(String adjustmentId, AccountId accountId, Money totalCredited) {
    }
}
