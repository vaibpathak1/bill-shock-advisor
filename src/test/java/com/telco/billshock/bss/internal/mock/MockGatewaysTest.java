package com.telco.billshock.bss.internal.mock;

import com.telco.billshock.bss.CustomerBillGateway.AdjustmentRequest;
import com.telco.billshock.bss.CustomerBillGateway.AdjustmentType;
import com.telco.billshock.bss.ProductInventoryGateway.ActiveProduct;
import com.telco.billshock.bss.ProductInventoryGateway.OptInEvidence;
import com.telco.billshock.bss.ProductInventoryGateway.OrderAction;
import com.telco.billshock.bss.ProductInventoryGateway.OrderRequest;
import com.telco.billshock.bss.ProductInventoryGateway.ProductType;
import com.telco.billshock.bss.TroubleTicketGateway.TicketRequest;
import com.telco.billshock.bss.TroubleTicketGateway.TicketType;
import com.telco.billshock.bss.UsageGateway.UsageKind;
import com.telco.billshock.bss.UsageGateway.UsageSession;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.Money;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The fixtures of seed-scenarios.md §6, served by the in-process mocks. */
class MockGatewaysTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void vasWithoutConfirmationIsNotDoubleOptIn() {
        List<ActiveProduct> products = new MockProductInventoryGateway(jsonMapper).activeProducts(AccountId.of(1003));

        assertThat(products).filteredOn(p -> p.type() == ProductType.VAS)
            .extracting(ActiveProduct::code, p -> p.optInEvidence().map(OptInEvidence::isDoubleOptIn).orElseThrow())
            .containsExactlyInAnyOrder(
                    org.assertj.core.groups.Tuple.tuple("CRICKET_SCORES", true),
                    org.assertj.core.groups.Tuple.tuple("ASTRO_DAILY", false));
        assertThat(products).filteredOn(p -> p.code().equals("ASTRO_DAILY")).singleElement()
            .satisfies(p -> {
                assertThat(p.activationChannel()).isEqualTo("WAP");
                assertThat(p.optInEvidence().orElseThrow().confirmationAt()).isNull();
                assertThat(p.price()).isEqualTo(Money.of("49.00"));
            });
    }

    @Test
    void planChangeOrderExistsForTheProrationScenario() {
        var gateway = new MockProductInventoryGateway(jsonMapper);

        assertThat(gateway.orders(AccountId.of(1004))).singleElement().satisfies(order -> {
            assertThat(order.action()).isEqualTo(OrderAction.PLAN_CHANGE);
            assertThat(order.fromCode()).isEqualTo("PP_399");
            assertThat(order.toCode()).isEqualTo("PP_699");
            assertThat(order.effectiveDate()).hasToString("2026-09-01");
        });
        assertThat(gateway.activeProducts(AccountId.of(9999))).isEmpty();
    }

    @Test
    void ordersAreIdempotentPerKey() {
        var gateway = new MockProductInventoryGateway(jsonMapper);
        var request = new OrderRequest(AccountId.of(1003), OrderAction.VAS_UNSUBSCRIBE, "SUB-1003-VAS-ASTRO", null, "key-1");

        var first = gateway.submitOrder(request);
        var second = gateway.submitOrder(request);

        assertThat(second).isEqualTo(first);
        assertThat(gateway.submittedOrders()).hasSize(1);
    }

    @Test
    void adjustmentsIncludeGstAndAreIdempotent() {
        var gateway = new MockCustomerBillGateway();
        var refund = new AdjustmentRequest(AccountId.of(1003), BillPeriod.of(2026, 9), AdjustmentType.REFUND,
                Money.of("196.00"), Money.of("35.28"), "VAS without double opt-in", "key-2");

        var receipt = gateway.requestAdjustment(refund);
        gateway.requestAdjustment(refund);

        assertThat(receipt.totalCredited()).isEqualTo(Money.of("231.28"));
        assertThat(gateway.appliedAdjustments()).hasSize(1);
    }

    @Test
    void ticketsAreIdempotent() {
        var gateway = new MockTroubleTicketGateway();
        var dispute = new TicketRequest(AccountId.of(1005), TicketType.BILLING_DISPUTE, BillPeriod.of(2026, 9),
                List.of(1005260902L), "Duplicate rental", "key-3");

        assertThat(gateway.createTicket(dispute)).isEqualTo(gateway.createTicket(dispute));
        assertThat(gateway.createdTickets()).hasSize(1);
    }

    @Test
    void usageDetailMatchesTheSeededAggregates() {
        var gateway = new MockUsageGateway(jsonMapper);

        List<UsageSession> trip = gateway.usageSessions(AccountId.of(1001), BillPeriod.of(2026, 9));
        assertThat(total(trip)).isEqualTo(Money.of("1775.00"));
        assertThat(trip).allSatisfy(s -> assertThat(s.countryCode()).isEqualTo("AE"));
        assertThat(quantity(trip, UsageKind.ROAMING_DATA)).isEqualByComparingTo("550");
        assertThat(quantity(trip, UsageKind.ROAMING_VOICE)).isEqualByComparingTo("10");
        assertThat(quantity(trip, UsageKind.ROAMING_SMS)).isEqualByComparingTo("3");

        List<UsageSession> data = gateway.usageSessions(AccountId.of(1002), BillPeriod.of(2026, 9));
        assertThat(total(data)).isEqualTo(Money.of("368.64"));
        assertThat(quantity(data, UsageKind.DATA)).isEqualByComparingTo(String.valueOf(58 * 1024));

        assertThat(gateway.usageSessions(AccountId.of(1006), BillPeriod.of(2026, 9))).isEmpty();
    }

    @Test
    void catalogueHasEightPlansAndFiveAddOns() {
        var gateway = new MockProductCatalogGateway(jsonMapper);

        assertThat(gateway.plans()).hasSize(8);
        assertThat(gateway.addOns()).hasSize(5);
    }

    private static Money total(List<UsageSession> sessions) {
        return sessions.stream().map(UsageSession::charge).reduce(Money.ZERO, Money::plus);
    }

    private static BigDecimal quantity(List<UsageSession> sessions, UsageKind kind) {
        return sessions.stream().filter(s -> s.kind() == kind).map(UsageSession::quantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
