package com.telco.billshock.bss.internal.mock;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.telco.billshock.bss.BssRejectedException;
import com.telco.billshock.bss.BssUnavailableException;
import com.telco.billshock.bss.CustomerBillGateway;
import com.telco.billshock.bss.ProductInventoryGateway;
import com.telco.billshock.bss.TroubleTicketGateway;

/**
 * Test-only fault injection around the mock BSS gateways (actions.md §9): the next call to a
 * gateway can lose its response after the mock applied it, fail before applying, or be definitely
 * rejected. It also counts the effects the mocks really applied per idempotency key, so a test can
 * assert "exactly one BSS effect". Imported by every integration test; with no fault queued it only
 * passes calls through.
 */
@TestConfiguration(proxyBeanMethods = false)
public class BssFaults {

    public enum Gateway {
        BILLING, INVENTORY, TICKETS
    }

    public enum Fault {
        /** The mock applies the request, then the caller sees a timeout (the response was lost). */
        LOSE_RESPONSE_AFTER_APPLY,
        /** A timeout before anything reached the mock. */
        FAIL_BEFORE_APPLY,
        /** A definite business rejection; nothing applied. */
        REJECT
    }

    private static final Map<Gateway, Queue<Fault>> FAULTS = new ConcurrentHashMap<>();

    private static volatile MockCustomerBillGateway billing;
    private static volatile MockProductInventoryGateway inventory;
    private static volatile MockTroubleTicketGateway tickets;

    /** Queues a fault for the next call to {@code gateway}. */
    public static void next(Gateway gateway, Fault fault) {
        FAULTS.computeIfAbsent(gateway, g -> new ConcurrentLinkedQueue<>()).add(fault);
    }

    public static void reset() {
        FAULTS.clear();
    }

    /** Adjustments (credits, refunds) the mock applied with this key. */
    public static List<CustomerBillGateway.AdjustmentRequest> adjustments(String key) {
        return billing.appliedAdjustments().stream().filter(r -> r.idempotencyKey().equals(key)).toList();
    }

    public static List<ProductInventoryGateway.OrderRequest> orders(String key) {
        return inventory.submittedOrders().stream().filter(r -> r.idempotencyKey().equals(key)).toList();
    }

    public static List<TroubleTicketGateway.TicketRequest> tickets(String key) {
        return tickets.createdTickets().stream().filter(r -> r.idempotencyKey().equals(key)).toList();
    }

    @Bean
    static BeanPostProcessor bssFaultInjection() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return switch (bean) {
                    case MockCustomerBillGateway mock -> {
                        billing = mock;
                        yield (CustomerBillGateway) request -> call(Gateway.BILLING, () -> mock.requestAdjustment(request));
                    }
                    case MockProductInventoryGateway mock -> {
                        inventory = mock;
                        yield new ProductInventoryGateway() {
                            @Override
                            public List<ActiveProduct> activeProducts(com.telco.billshock.domain.AccountId accountId) {
                                return mock.activeProducts(accountId);
                            }

                            @Override
                            public List<ProductOrder> orders(com.telco.billshock.domain.AccountId accountId) {
                                return mock.orders(accountId);
                            }

                            @Override
                            public OrderReceipt submitOrder(OrderRequest request) {
                                return call(Gateway.INVENTORY, () -> mock.submitOrder(request));
                            }
                        };
                    }
                    case MockTroubleTicketGateway mock -> {
                        tickets = mock;
                        yield (TroubleTicketGateway) request -> call(Gateway.TICKETS, () -> mock.createTicket(request));
                    }
                    default -> bean;
                };
            }
        };
    }

    private static <T> T call(Gateway gateway, Supplier<T> delegate) {
        Queue<Fault> queue = FAULTS.get(gateway);
        Fault fault = queue == null ? null : queue.poll();
        if (fault == null) {
            return delegate.get();
        }
        switch (fault) {
            case LOSE_RESPONSE_AFTER_APPLY -> {
                delegate.get();
                throw new BssUnavailableException("Injected: response lost after the BSS applied the request");
            }
            case FAIL_BEFORE_APPLY -> throw new BssUnavailableException("Injected: timeout before the BSS was reached");
            case REJECT -> throw new BssRejectedException("TEST_REJECTED", "Injected: definite rejection");
            default -> throw new IllegalStateException(fault.name());
        }
    }
}
