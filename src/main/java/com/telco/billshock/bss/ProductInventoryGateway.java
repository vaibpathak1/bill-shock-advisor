package com.telco.billshock.bss;

import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.Money;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * TMF622 Product Ordering (and the product inventory behind it): active subscriptions with
 * their opt-in evidence, past orders, and new orders (plan change, add-on, VAS
 * unsubscribe, third-party barring). Not stored locally (data-architecture.md §1).
 */
public interface ProductInventoryGateway {

    List<ActiveProduct> activeProducts(AccountId accountId);

    List<ProductOrder> orders(AccountId accountId);

    /**
     * @throws BssRejectedException the BSS definitely did not apply the request
     * @throws BssUnavailableException the outcome is unknown (timeout, connection error); retry with the same key
     */
    OrderReceipt submitOrder(OrderRequest request);

    enum ProductType {
        PLAN, ADD_ON, VAS, SERVICE
    }

    enum OrderAction {
        PLAN_CHANGE, ADD_ON, VAS_UNSUBSCRIBE, THIRD_PARTY_BARRING
    }

    /**
     * Double opt-in evidence for a VAS subscription (PRD glossary). A missing step is
     * {@code null}; callers must say explicitly that evidence is missing (SPEC §4.7).
     */
    record OptInEvidence(OffsetDateTime firstConsentAt, String firstConsentChannel,
            OffsetDateTime confirmationAt, String confirmationChannel) {

        public boolean isDoubleOptIn() {
            return firstConsentAt != null && confirmationAt != null;
        }
    }

    /**
     * @param provider third-party provider name; untrusted text (security.md §5)
     * @param price before GST, for the product's own billing unit
     * @param optInEvidence present for VAS products only
     */
    record ActiveProduct(String subscriptionId, ProductType type, String code, String name,
            String provider, Money price, LocalDate activatedOn, String activationChannel,
            Optional<OptInEvidence> optInEvidence) {
    }

    record ProductOrder(String orderId, OrderAction action, String fromCode, String toCode,
            LocalDate effectiveDate, String channel, String status) {
    }

    /**
     * @param subscriptionId the subscription the order changes (VAS unsubscribe), or {@code null}
     * @param productCode the plan or add-on ordered, or {@code null}
     * @param effective {@code IMMEDIATE} or {@code NEXT_CYCLE} for a plan change, else {@code null}
     * @param idempotencyKey repeated requests with the same key must not order twice
     */
    record OrderRequest(AccountId accountId, OrderAction action, String subscriptionId,
            String productCode, String effective, String idempotencyKey) {
    }

    record OrderReceipt(String orderId, String status) {
    }
}
