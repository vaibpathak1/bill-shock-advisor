package com.telco.billshock.bss.internal.mock;

import com.telco.billshock.bss.ProductInventoryGateway;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.Money;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mock TMF622: subscriptions with opt-in evidence and past orders from
 * {@code inventory.json}. New orders are recorded in memory and are idempotent per
 * idempotency key; they do not change the fixture data.
 */
@Component
@Profile("mock-bss")
class MockProductInventoryGateway implements ProductInventoryGateway {

    private final Map<String, AccountFixture> accounts;
    private final List<OrderRequest> submitted = new CopyOnWriteArrayList<>();
    private final Map<String, OrderReceipt> receiptsByKey = new ConcurrentHashMap<>();
    private final AtomicLong orderSequence = new AtomicLong();

    MockProductInventoryGateway(JsonMapper jsonMapper) {
        this.accounts = BssFixtures.load(jsonMapper, "inventory.json", InventoryFixture.class).accounts();
    }

    @Override
    public List<ActiveProduct> activeProducts(AccountId accountId) {
        return account(accountId).products().stream().map(MockProductInventoryGateway::toProduct).toList();
    }

    @Override
    public List<ProductOrder> orders(AccountId accountId) {
        return List.copyOf(account(accountId).orders());
    }

    @Override
    public OrderReceipt submitOrder(OrderRequest request) {
        return receiptsByKey.computeIfAbsent(request.idempotencyKey(), key -> {
            submitted.add(request);
            return new OrderReceipt("MOCK-ORD-" + orderSequence.incrementAndGet(), "ACKNOWLEDGED");
        });
    }

    /** Orders received so far (for tests and the 6a executors). */
    List<OrderRequest> submittedOrders() {
        return List.copyOf(submitted);
    }

    private AccountFixture account(AccountId accountId) {
        return accounts.getOrDefault(String.valueOf(accountId.value()), AccountFixture.EMPTY);
    }

    private static ActiveProduct toProduct(ProductFixture p) {
        return new ActiveProduct(p.subscriptionId(), p.type(), p.code(), p.name(), p.provider(), Money.of(p.price()),
                p.activatedOn(), p.activationChannel(), Optional.ofNullable(p.optInEvidence()));
    }

    record InventoryFixture(Map<String, AccountFixture> accounts) {
    }

    record AccountFixture(List<ProductFixture> products, List<ProductOrder> orders) {

        static final AccountFixture EMPTY = new AccountFixture(List.of(), List.of());
    }

    record ProductFixture(String subscriptionId, ProductType type, String code, String name, String provider,
            BigDecimal price, LocalDate activatedOn, String activationChannel, OptInEvidence optInEvidence) {
    }
}
