package com.telco.billshock.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.telco.billshock.bss.CustomerBillGateway.AdjustmentType;
import com.telco.billshock.bss.ProductInventoryGateway.OrderAction;
import com.telco.billshock.bss.TroubleTicketGateway.TicketType;
import com.telco.billshock.bss.internal.mock.BssFaults;
import com.telco.billshock.bss.internal.mock.BssFaults.Fault;
import com.telco.billshock.bss.internal.mock.BssFaults.Gateway;
import com.telco.billshock.support.ActionClient;
import com.telco.billshock.support.FakeAnthropicApi;
import com.telco.billshock.support.FakeAnthropicApi.Script;
import com.telco.billshock.support.IntegrationTest;
import com.telco.billshock.support.ScenarioScripts;
import com.telco.billshock.support.SseClient;
import com.telco.billshock.support.SseClient.Response;

/**
 * One stubbed-LLM end-to-end test per seed scenario (SPEC §11 demo wrap-up; actions.md §8.3):
 * chat over SSE with the {@link ScenarioScripts} the scripted demo uses, the proposal as an SSE
 * {@code action} event, then confirm over HTTP, the resulting state and the mock BSS effects.
 */
@IntegrationTest
class ScenarioE2EIT {

    private static final FakeAnthropicApi API = FakeAnthropicApi.instance();

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    SseClient chat;
    ActionClient actions;

    @BeforeEach
    void setUp() {
        API.reset();
        API.select(ScenarioScripts::select);
        BssFaults.reset();
        // Actions are not audit data: each scenario starts without earlier proposals (dedupe, prior credits).
        jdbc.sql("DELETE FROM idempotency_record").update();
        jdbc.sql("DELETE FROM proposed_action").update();
        chat = new SseClient(port);
        actions = new ActionClient(port);
    }

    @Test
    void scenario1RoamingGoodwillCreditWaitsForASupervisor() {
        Response r = chat.chat("cust1001", null, "Why is my bill so high?");

        assertAnsweredByTheModel(r);
        assertThat(r.tokens()).contains("saved ₹1,033.68 incl. GST").contains("needs your confirmation");
        JsonNode action = r.first("action").data();
        assertThat(action.get("type").asString()).isEqualTo("GOODWILL_CREDIT");
        assertThat(action.get("status").asString()).isEqualTo("PENDING_CONFIRMATION");
        // Retroactively applying the pack: pay-per-use roaming minus the pack price, ₹876.00 + ₹157.68 GST.
        assertThat(action.get("amount").asString()).isEqualTo("₹1,033.68 incl. GST");
        assertThat(action.get("needsSupervisorReview").asBoolean()).isTrue();
        assertThat(action.get("summary").asString()).isEqualTo("Goodwill credit on the roaming charges of your"
                + " September 2026 bill");

        long id = action.get("actionId").asLong();
        ActionClient.Response confirmed = actions.confirm("cust1001", id, "s1-" + UUID.randomUUID());
        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.json().get("status").asString()).isEqualTo("AWAITING_SUPERVISOR");
        assertThat(BssFaults.adjustments("pa-" + id)).isEmpty(); // nothing credited before a supervisor approves (6b)
        assertThat(jdbc.sql("SELECT amount || '+' || gst_amount FROM proposed_action WHERE action_id = ?")
            .param(id)
            .query(String.class)
            .single()).isEqualTo("876.00+157.68");
    }

    @Test
    void scenario2PlanChangeExecutesAsATmf622Order() {
        Response r = chat.chat("cust1002", null, "Why is my bill so high?");

        assertAnsweredByTheModel(r);
        JsonNode action = r.first("action").data();
        assertThat(action.get("type").asString()).isEqualTo("PLAN_CHANGE");
        assertThat(action.get("summary").asString()).isEqualTo("Change your plan to PP_499 from your next bill cycle");

        long id = action.get("actionId").asLong();
        ActionClient.Response confirmed = actions.confirm("cust1002", id, "s2-" + UUID.randomUUID());
        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.json().get("status").asString()).isEqualTo("EXECUTED");
        assertThat(BssFaults.orders("pa-" + id)).singleElement().satisfies(o -> {
            assertThat(o.action()).isEqualTo(OrderAction.PLAN_CHANGE);
            assertThat(o.productCode()).isEqualTo("PP_499");
            assertThat(o.effective()).isEqualTo("NEXT_CYCLE");
        });
    }

    @Test
    void scenario3VasRefundExecutesFullyOnceAndTheNextTurnMayTellTheCustomer() {
        Response r = chat.chat("cust1003", null, "Why is my bill so high?");

        assertAnsweredByTheModel(r);
        assertThat(r.named("action")).extracting(e -> e.data().get("type").asString())
            .containsExactly("VAS_UNSUBSCRIBE", "THIRD_PARTY_BARRING");
        JsonNode vas = r.first("action").data();
        assertThat(vas.get("amount").asString()).isEqualTo("₹231.28 incl. GST");
        assertThat(vas.get("summary").asString()).isEqualTo("Unsubscribe from Astro Daily and refund its charges");

        long id = vas.get("actionId").asLong();
        String key = "s3-" + UUID.randomUUID();
        ActionClient.Response confirmed = actions.confirm("cust1003", id, key);
        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.json().get("status").asString()).isEqualTo("EXECUTED");
        assertThat(confirmed.json().get("execution")).extracting(s -> s.get("effect").asString() + ":"
                + s.get("status").asString()).containsExactly("UNSUBSCRIBE:DONE", "REFUND:DONE");
        assertThat(BssFaults.orders("pa-" + id)).singleElement()
            .satisfies(o -> assertThat(o.subscriptionId()).isEqualTo("SUB-1003-VAS-ASTRO"));
        assertThat(BssFaults.adjustments("pa-" + id)).singleElement().satisfies(a -> {
            assertThat(a.type()).isEqualTo(AdjustmentType.REFUND);
            assertThat(a.amountExclGst().amount()).isEqualByComparingTo("196.00");
            assertThat(a.gstAmount().amount()).isEqualByComparingTo("35.28");
        });

        // Confirming twice never refunds twice (SPEC §4.3 rule 4): the same response, no new effect.
        ActionClient.Response replay = actions.confirm("cust1003", id, key);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(confirmed.body());
        assertThat(BssFaults.adjustments("pa-" + id)).hasSize(1);

        // Turn 2: the unsubscribe and the refund are DONE receipts, so the claim passes the gate.
        UUID conversationId = UUID.fromString(r.first("done").data().get("conversationId").asString());
        Response second = chat.chat("cust1003", conversationId.toString(), "Is it done?");
        assertThat(second.names()).doesNotContain("fallback");
        assertThat(second.tokens()).isEqualTo("Astro Daily has been cancelled and ₹231.28 incl. GST has been refunded"
                + " to your account.");
        assertThat(API.requests().getLast()).contains("Status EXECUTED").contains("Status PENDING_CONFIRMATION");
    }

    /** Owner addition (6a design review): unsubscribe done, refund definitely rejected. */
    @Test
    void scenario3PartialExecutionAllowsTheUnsubscribeClaimAndBlocksTheRefundClaim() {
        Response r = chat.chat("cust1003", null, "Why is my bill so high?");
        long id = r.first("action").data().get("actionId").asLong();

        BssFaults.next(Gateway.BILLING, Fault.REJECT);
        ActionClient.Response confirmed = actions.confirm("cust1003", id, "s3p-" + UUID.randomUUID());
        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.json().get("status").asString()).isEqualTo("FAILED");
        assertThat(confirmed.json().get("message").asString()).isEqualTo("The subscription is cancelled. The refund"
                + " could not be processed automatically; our billing team will handle it manually.");
        assertThat(confirmed.json().get("execution")).extracting(s -> s.get("effect").asString() + ":"
                + s.get("status").asString()).containsExactly("UNSUBSCRIBE:DONE", "REFUND:REJECTED");
        assertThat(BssFaults.orders("pa-" + id)).hasSize(1);
        assertThat(BssFaults.adjustments("pa-" + id)).isEmpty();

        UUID conversationId = UUID.fromString(r.first("done").data().get("conversationId").asString());
        API.enqueue(Script.text("Astro Daily has been cancelled. Your refund has been processed."));
        Response second = chat.chat("cust1003", conversationId.toString(), "What happened?");
        assertThat(second.names()).doesNotContain("fallback");
        assertThat(second.tokens()).startsWith("Astro Daily has been cancelled. ")
            .contains("Only the steps shown as done in your requests have been carried out")
            .doesNotContain("refund has been processed");
        // The model is told the same thing the customer saw.
        assertThat(API.requests().getLast()).contains("The subscription is cancelled. The refund could not be"
                + " processed automatically");
    }

    @Test
    void scenario4ProrationIsExplainedWithNoAction() {
        Response r = chat.chat("cust1004", null, "Why is my bill so high?");

        assertAnsweredByTheModel(r);
        assertThat(r.tokens()).contains("nothing needs to change");
        assertThat(r.names()).doesNotContain("action");
        assertThat(actionsOf(1004)).isZero();
    }

    @Test
    void scenario5DuplicateChargeIsDisputedAsATicket() {
        Response r = chat.chat("cust1005", null, "Why is my bill so high?");

        assertAnsweredByTheModel(r);
        JsonNode action = r.first("action").data();
        assertThat(action.get("type").asString()).isEqualTo("DISPUTE");
        assertThat(action.get("amount").asString()).isEqualTo("₹706.82 incl. GST");
        assertThat(r.tokens()).contains("dispute of ₹706.82 incl. GST");

        long id = action.get("actionId").asLong();
        ActionClient.Response confirmed = actions.confirm("cust1005", id, "s5-" + UUID.randomUUID());
        assertThat(confirmed.json().get("status").asString()).isEqualTo("EXECUTED");
        assertThat(BssFaults.tickets("pa-" + id)).singleElement().satisfies(t -> {
            assertThat(t.type()).isEqualTo(TicketType.BILLING_DISPUTE);
            assertThat(t.lineItemIds()).isEqualTo(List.of(1005260902L));
        });
        // No goodwill credit for the same amount (US-RES-03).
        assertThat(jdbc.sql("SELECT count(*) FROM proposed_action WHERE account_id = 1005"
                + " AND action_type = 'GOODWILL_CREDIT'").query(Integer.class).single()).isZero();
    }

    @Test
    void scenario6NormalBillHasNoAction() {
        Response r = chat.chat("cust1006", null, "Why is my bill so high?");

        assertAnsweredByTheModel(r);
        assertThat(r.tokens()).contains("in line with your recent bills");
        assertThat(r.names()).doesNotContain("action");
        assertThat(actionsOf(1006)).isZero();
    }

    private static void assertAnsweredByTheModel(Response r) {
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.names()).doesNotContain("fallback", "reset", "error");
        assertThat(r.tokens()).doesNotContain(ScenarioScripts.UNKNOWN_SCENARIO, ScenarioScripts.NO_MORE_TURNS);
        assertThat(r.names().getLast()).isEqualTo("done");
    }

    private int actionsOf(long account) {
        return jdbc.sql("SELECT count(*) FROM proposed_action WHERE account_id = ?")
            .param(account)
            .query(Integer.class)
            .single();
    }
}
