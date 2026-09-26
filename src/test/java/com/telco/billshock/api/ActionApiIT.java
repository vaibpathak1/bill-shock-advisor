package com.telco.billshock.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.telco.billshock.actions.ActionExecution;
import com.telco.billshock.actions.ActionRequest;
import com.telco.billshock.actions.ActionStatus;
import com.telco.billshock.actions.ActionView;
import com.telco.billshock.actions.ProposalResult;
import com.telco.billshock.actions.ProposedActionService;
import com.telco.billshock.bss.internal.mock.BssFaults;
import com.telco.billshock.bss.internal.mock.BssFaults.Fault;
import com.telco.billshock.bss.internal.mock.BssFaults.Gateway;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.Money;
import com.telco.billshock.support.ActionClient;
import com.telco.billshock.support.ActionClient.Response;
import com.telco.billshock.support.IntegrationTest;

/**
 * The action REST contract and workflow over HTTP (actions.md §4, §6, §9): idempotency, the
 * unknown-outcome path and re-drive, concurrency, dedupe, expiry, escalation tickets and the audit
 * trail. Proposals are created through the service (the chat path is covered by {@link ScenarioE2EIT}).
 */
@IntegrationTest
class ActionApiIT {

    private static final AccountId A1001 = AccountId.of(1001);
    private static final AccountId A1002 = AccountId.of(1002);
    private static final AccountId A1003 = AccountId.of(1003);
    private static final AccountId A1005 = AccountId.of(1005);
    private static final ActionRequest ASTRO_REFUND = new ActionRequest.VasUnsubscribe("SUB-1003-VAS-ASTRO", true);

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProposedActionService service;

    @Autowired
    ActionExecution execution;

    ActionClient http;

    @BeforeEach
    void setUp() {
        clean();
        http = new ActionClient(port);
    }

    @AfterEach
    void clean() {
        BssFaults.reset();
        jdbc.sql("DELETE FROM idempotency_record").update();
        jdbc.sql("DELETE FROM proposed_action").update();
    }

    private long propose(AccountId account, ActionRequest request) {
        ProposalResult r = service.propose(account, null, request);
        return r.action().orElseThrow(() -> new AssertionError("Not proposed: " + r.reasons())).actionId();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void confirmIsIdempotentAndEveryErrorIsAProblemWithACode() {
        long id = propose(A1003, ASTRO_REFUND);

        Response noKey = http.confirm("cust1003", id, null);
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(noKey.header("Content-Type")).startsWith("application/problem+json");
        assertThat(noKey.json().get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(http.confirm("cust1003", id, "not a valid key!").status()).isEqualTo(400);
        assertThat(http.confirm("cust1003", 987654321L, key()).json().get("code").asString())
            .isEqualTo("ACTION_NOT_FOUND");
        // Another customer's action is not found either (the same 404).
        Response foreign = http.confirm("cust1002", id, key());
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(BssFaults.orders("pa-" + id)).isEmpty();

        String k1 = key();
        Response first = http.confirm("cust1003", id, k1);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.header("Content-Type")).startsWith("application/json");
        assertThat(first.json().get("status").asString()).isEqualTo("EXECUTED");
        assertThat(first.json().get("amount").get("display").asString()).isEqualTo("₹231.28 incl. GST");
        assertThat(first.json().has("reasons")).isFalse(); // no guardrail codes (SPEC §4.5)

        Response replay = http.confirm("cust1003", id, k1);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(http.reject("cust1003", id, k1, null).json().get("code").asString())
            .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        Response again = http.confirm("cust1003", id, key());
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.json().get("code").asString()).isEqualTo("ACTION_NOT_PENDING");

        assertThat(BssFaults.orders("pa-" + id)).hasSize(1);
        assertThat(BssFaults.adjustments("pa-" + id)).hasSize(1);
        assertThat(auditTrail(id)).containsExactly("ACTION_PROPOSED AGENT", "GUARDRAIL_RECHECKED SYSTEM",
                "ACTION_CONFIRMED CUSTOMER cust1003", "ACTION_APPROVED SYSTEM", "ACTION_EXECUTING SYSTEM",
                "ACTION_EXECUTED SYSTEM");
        assertThat(jdbc.sql("SELECT decided_by FROM proposed_action WHERE action_id = ?").param(id)
            .query(String.class).single()).isEqualTo("cust1003");
    }

    @Test
    void aRejectedProposalCannotBeConfirmed() {
        long id = propose(A1003, new ActionRequest.ThirdPartyBarring());

        Response rejected = http.reject("cust1003", id, key(), "Not now, my number is 9876543210");
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.json().get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.json().get("message").asString()).isEqualTo("Rejected. Nothing was changed on your account.");
        assertThat(http.confirm("cust1003", id, key()).json().get("code").asString()).isEqualTo("ACTION_NOT_PENDING");
        assertThat(BssFaults.orders("pa-" + id)).isEmpty();
        // The customer's free text reaches the audit log PII-scrubbed.
        assertThat(jdbc.sql("SELECT payload::text FROM audit_events WHERE action_id = ? AND event_type = 'ACTION_REJECTED'")
            .param(id).query(String.class).single()).contains("[PHONE]").doesNotContain("9876543210");
    }

    @Test
    void anExpiredProposalCannotBeConfirmedAndListsAsExpired() {
        long confirmLate = propose(A1003, new ActionRequest.ThirdPartyBarring());
        long listLate = propose(A1003, new ActionRequest.AddOnPurchase("DATA_10GB"));
        jdbc.sql("UPDATE proposed_action SET expires_at = now() - interval '1 minute'").update();

        Response late = http.confirm("cust1003", confirmLate, key());
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.json().get("code").asString()).isEqualTo("ACTION_EXPIRED");
        assertThat(statusOf(confirmLate)).isEqualTo("EXPIRED");

        Response list = http.get("cust1003", "/api/v1/actions?status=EXPIRED");
        assertThat(list.json().get("items")).extracting(i -> i.get("actionId").asLong())
            .containsExactlyInAnyOrder(confirmLate, listLate);
        assertThat(auditTrail(listLate)).contains("ACTION_EXPIRED SYSTEM");
        assertThat(BssFaults.orders("pa-" + confirmLate)).isEmpty();
    }

    @Test
    void listIsPagedFilteredAndScopedToTheCustomer() {
        propose(A1003, ASTRO_REFUND);
        propose(A1003, new ActionRequest.ThirdPartyBarring());
        long newest = propose(A1003, new ActionRequest.AddOnPurchase("DATA_10GB"));

        Response page0 = http.get("cust1003", "/api/v1/actions?size=2");
        assertThat(page0.status()).isEqualTo(200);
        assertThat(page0.json().get("items")).hasSize(2);
        assertThat(page0.json().get("items").get(0).get("actionId").asLong()).isEqualTo(newest);
        assertThat(page0.json().get("totalElements").asLong()).isEqualTo(3);
        assertThat(http.get("cust1003", "/api/v1/actions?size=2&page=1").json().get("items")).hasSize(1);
        assertThat(http.get("cust1003", "/api/v1/actions?status=EXECUTED").json().get("items")).isEmpty();
        assertThat(http.get("cust1002", "/api/v1/actions").json().get("totalElements").asLong()).isZero();
        assertThat(http.get("cust1003", "/api/v1/actions?size=0").json().get("code").asString())
            .isEqualTo("INVALID_PAGE");
        assertThat(http.get("cust1003", "/api/v1/actions?status=BOGUS").status()).isEqualTo(400);
        assertThat(http.get(null, "/api/v1/actions").status()).isEqualTo(401);
    }

    /** Owner answer 4: a changed amount is never executed; the customer is told and offered a fresh proposal. */
    @Test
    void aChangedAmountIsRejectedWith409AndBothAmounts() {
        long id = propose(A1003, ASTRO_REFUND);
        // As if the proposal had been made before the latest charges: the re-check now computes ₹231.28.
        jdbc.sql("UPDATE proposed_action SET amount = 150.00, gst_amount = 27.00 WHERE action_id = ?").param(id).update();

        Response r = http.confirm("cust1003", id, key());

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.json().get("code").asString()).isEqualTo("ACTION_NO_LONGER_VALID");
        assertThat(r.json().get("reason").asString()).isEqualTo("AMOUNT_CHANGED");
        assertThat(r.json().get("proposedAmount").asString()).isEqualTo("₹177.00 incl. GST");
        assertThat(r.json().get("currentAmount").asString()).isEqualTo("₹231.28 incl. GST");
        assertThat(r.json().get("detail").asString()).isEqualTo("The amount has changed since this was proposed."
                + " Nothing was changed on your account. Ask for a fresh proposal.");
        assertThat(statusOf(id)).isEqualTo("REJECTED");
        assertThat(BssFaults.orders("pa-" + id)).isEmpty();
        // The target is free again: a fresh proposal can be made.
        assertThat(service.propose(A1003, null, ASTRO_REFUND).kind()).isEqualTo(ProposalResult.Kind.PROPOSED);
    }

    @Test
    void aTargetHoldsOneLiveActionAndDisputesDedupeByLineItems() {
        long vas = propose(A1003, ASTRO_REFUND);
        ProposalResult again = service.propose(A1003, null, ASTRO_REFUND);
        assertThat(again.kind()).isEqualTo(ProposalResult.Kind.ALREADY_PROPOSED);
        assertThat(again.action().orElseThrow().actionId()).isEqualTo(vas);
        http.confirm("cust1003", vas, key());
        // Executed money-out blocks a repeat: no second refund, ever.
        assertThat(service.propose(A1003, null, ASTRO_REFUND).kind()).isEqualTo(ProposalResult.Kind.ALREADY_PROPOSED);

        long dispute = propose(A1005, new ActionRequest.Dispute(List.of(1005260902L), "charged twice"));
        ProposalResult overlapping = service.propose(A1005, null,
                new ActionRequest.Dispute(List.of(1005260901L, 1005260902L), "charged twice"));
        assertThat(overlapping.kind()).isEqualTo(ProposalResult.Kind.ALREADY_PROPOSED);
        assertThat(overlapping.action().orElseThrow().actionId()).isEqualTo(dispute);
        // Owner answer 3: a dispute on a different charge of the same bill is not blocked.
        assertThat(service.propose(A1005, null, new ActionRequest.Dispute(List.of(1005260901L), "other")).kind())
            .isEqualTo(ProposalResult.Kind.PROPOSED);

        // A-114: one live plan change at a time.
        long plan = propose(A1002, new ActionRequest.PlanChange("PP_499", ActionRequest.Effective.NEXT_CYCLE));
        assertThat(service.propose(A1002, null, new ActionRequest.PlanChange("PP_599", ActionRequest.Effective.NEXT_CYCLE))
            .action().orElseThrow().actionId()).isEqualTo(plan);
    }

    /** Owner change: an unknown outcome stays EXECUTING; a re-drive with the same key gives exactly one effect. */
    @Test
    void aLostResponseStaysExecutingAndTheRedriveAppliesItOnce() {
        long id = propose(A1003, ASTRO_REFUND);
        String k = key();
        BssFaults.next(Gateway.INVENTORY, Fault.LOSE_RESPONSE_AFTER_APPLY);

        Response r = http.confirm("cust1003", id, k);

        assertThat(r.status()).isEqualTo(202);
        assertThat(r.json().get("status").asString()).isEqualTo("EXECUTING");
        assertThat(r.json().get("message").asString()).isEqualTo("We are checking with the billing system. Nothing"
                + " more is needed from you.");
        assertThat(BssFaults.orders("pa-" + id)).hasSize(1); // applied, although the answer was lost
        assertThat(BssFaults.adjustments("pa-" + id)).isEmpty();
        assertThat(auditTrail(id)).contains("ACTION_OUTCOME_UNKNOWN SYSTEM").doesNotContain("ACTION_FAILED SYSTEM");

        ActionView redriven = execution.redrive(id);

        assertThat(redriven.status()).isEqualTo(ActionStatus.EXECUTED);
        assertThat(BssFaults.orders("pa-" + id)).hasSize(1);
        assertThat(BssFaults.adjustments("pa-" + id)).hasSize(1);
        assertThat(auditTrail(id)).contains("ACTION_REDRIVEN SYSTEM", "ACTION_EXECUTED SYSTEM");
        // The stored confirm response is not rewritten (A-112); the current state is in the list.
        assertThat(http.confirm("cust1003", id, k).status()).isEqualTo(202);
        assertThat(http.get("cust1003", "/api/v1/actions").json().get("items").get(0).get("status").asString())
            .isEqualTo("EXECUTED");
    }

    @Test
    void aTimeoutBeforeTheBssStaysExecutingAndTheRedriveAppliesItOnce() {
        long id = propose(A1005, new ActionRequest.Dispute(List.of(1005260902L), "charged twice"));
        BssFaults.next(Gateway.TICKETS, Fault.FAIL_BEFORE_APPLY);

        assertThat(http.confirm("cust1005", id, key()).status()).isEqualTo(202);
        assertThat(statusOf(id)).isEqualTo("EXECUTING");
        assertThat(BssFaults.tickets("pa-" + id)).isEmpty();

        assertThat(execution.redrive(id).status()).isEqualTo(ActionStatus.EXECUTED);
        assertThat(BssFaults.tickets("pa-" + id)).hasSize(1);
        assertThatThrownBy(() -> execution.redrive(id)).isInstanceOf(IllegalStateException.class);
        assertThat(BssFaults.tickets("pa-" + id)).hasSize(1);
    }

    @Test
    void onlyADefiniteRejectionFailsAndAFailedActionIsNotRedriven() {
        long id = propose(A1002, new ActionRequest.PlanChange("PP_499", ActionRequest.Effective.NEXT_CYCLE));
        BssFaults.next(Gateway.INVENTORY, Fault.REJECT);

        Response r = http.confirm("cust1002", id, key());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("status").asString()).isEqualTo("FAILED");
        assertThat(r.json().get("message").asString()).isEqualTo("The billing system could not complete this."
                + " Nothing was changed on your account.");
        assertThat(jdbc.sql("SELECT failure_reason FROM proposed_action WHERE action_id = ?").param(id)
            .query(String.class).single()).isEqualTo("BSS_REJECTED:TEST_REJECTED");
        assertThatThrownBy(() -> execution.redrive(id)).isInstanceOf(IllegalStateException.class);
        assertThat(BssFaults.orders("pa-" + id)).isEmpty();
    }

    @Test
    void twoConfirmsWithDifferentKeysExecuteOnce() throws Exception {
        long id = propose(A1003, new ActionRequest.ThirdPartyBarring());

        List<Response> responses = concurrently(() -> http.confirm("cust1003", id, key()),
                () -> http.confirm("cust1003", id, key()));

        assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(200, 409);
        assertThat(BssFaults.orders("pa-" + id)).hasSize(1);
        assertThat(statusOf(id)).isEqualTo("EXECUTED");
    }

    @Test
    void twoConfirmsWithTheSameKeyExecuteOnce() throws Exception {
        long id = propose(A1003, new ActionRequest.ThirdPartyBarring());
        String k = key();

        List<Response> responses = concurrently(() -> http.confirm("cust1003", id, k),
                () -> http.confirm("cust1003", id, k));

        assertThat(BssFaults.orders("pa-" + id)).hasSize(1);
        assertThat(responses).anySatisfy(r -> assertThat(r.status()).isEqualTo(200));
        // The other one is the stored response or, if it arrived mid-execution, REQUEST_IN_PROGRESS.
        assertThat(responses).allSatisfy(r -> assertThat(r.status() == 200
                || "REQUEST_IN_PROGRESS".equals(r.json().path("code").asString())).isTrue());
    }

    @Test
    void twoConcurrentRedrivesCallTheBssOnce() throws Exception {
        long id = propose(A1003, new ActionRequest.ThirdPartyBarring());
        BssFaults.next(Gateway.INVENTORY, Fault.FAIL_BEFORE_APPLY);
        assertThat(http.confirm("cust1003", id, key()).status()).isEqualTo(202);

        concurrently(() -> redriveQuietly(id), () -> redriveQuietly(id));

        assertThat(BssFaults.orders("pa-" + id)).hasSize(1);
        assertThat(statusOf(id)).isEqualTo("EXECUTED");
    }

    @Test
    void anEscalationOpensOneTicketAtOnce() {
        // Above the money-out cap: ₹1,775.00 + GST = ₹2,094.50 incl. GST → escalated, no confirmation needed.
        ProposalResult big = service.propose(A1001, null, new ActionRequest.GoodwillCredit(Money.of("1775.00"),
                "roaming", List.of(1001260902L, 1001260903L, 1001260904L)));
        assertThat(big.kind()).isEqualTo(ProposalResult.Kind.ESCALATED);
        ActionView escalated = big.action().orElseThrow();
        assertThat(escalated.status()).isEqualTo(ActionStatus.ESCALATED);
        assertThat(escalated.reference()).startsWith("MOCK-TT-");
        assertThat(escalated.message()).isEqualTo("Handed to a customer care specialist (reference "
                + escalated.reference() + ").");
        assertThat(BssFaults.tickets("pa-" + escalated.actionId())).hasSize(1);

        long handOff = propose(A1001, new ActionRequest.Escalation("Roaming question", "customer asked"));
        ProposalResult repeated = service.propose(A1001, null, new ActionRequest.Escalation("Again", "asked again"));
        assertThat(repeated.kind()).isEqualTo(ProposalResult.Kind.ALREADY_PROPOSED);
        assertThat(repeated.action().orElseThrow().actionId()).isEqualTo(handOff);
        assertThat(BssFaults.tickets("pa-" + handOff)).hasSize(1);
    }

    @Test
    void anEscalationWhoseTicketFailedIsOpenedByTheRedrive() {
        BssFaults.next(Gateway.TICKETS, Fault.FAIL_BEFORE_APPLY);
        ActionView escalated = service.propose(A1002, null, new ActionRequest.Escalation("Data question", "asked"))
            .action()
            .orElseThrow();
        assertThat(escalated.status()).isEqualTo(ActionStatus.ESCALATED);
        assertThat(escalated.reference()).isNull();
        assertThat(escalated.message()).isEqualTo("A customer care specialist will follow up.");
        assertThat(auditTrail(escalated.actionId())).contains("TICKET_NOT_OPENED SYSTEM");

        ActionView redriven = execution.redrive(escalated.actionId());

        assertThat(redriven.reference()).startsWith("MOCK-TT-");
        assertThat(BssFaults.tickets("pa-" + escalated.actionId())).hasSize(1);
    }

    /** actions.md §5.2: a goodwill credit issued here (or still EXECUTING) counts as a prior credit. */
    @Test
    void aCreditIssuedHereNeedsASupervisorForTheNextOne() {
        ActionRequest small = new ActionRequest.GoodwillCredit(Money.of("100.00"), "roaming", List.of(1001260902L));
        ActionView first = service.propose(A1001, null, small).action().orElseThrow();
        assertThat(first.needsSupervisorReview()).isFalse(); // ₹118.00 incl. GST: within every limit
        jdbc.sql("DELETE FROM proposed_action").update();

        jdbc.sql("""
                INSERT INTO proposed_action (account_id, action_type, status, amount, gst_amount, target_ref, decided_at)
                VALUES (1001, 'GOODWILL_CREDIT', 'EXECUTING', 50.00, 9.00, 'goodwill:2026-08', now() - interval '10 days')""")
            .update();
        assertThat(service.propose(A1001, null, small).action().orElseThrow().needsSupervisorReview()).isTrue();
    }

    private Response redriveQuietly(long id) {
        try {
            execution.redrive(id);
        }
        catch (IllegalStateException e) {
            // The other re-drive finished first: nothing left to re-drive.
        }
        return null;
    }

    private static List<Response> concurrently(Callable<Response> a, Callable<Response> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Response>> futures = new ArrayList<>();
            for (Callable<Response> task : List.of(a, b)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Response> out = new ArrayList<>();
            for (Future<Response> f : futures) {
                out.add(f.get());
            }
            return out;
        }
        finally {
            pool.shutdownNow();
        }
    }

    private String statusOf(long id) {
        return jdbc.sql("SELECT status FROM proposed_action WHERE action_id = ?").param(id).query(String.class).single();
    }

    private List<String> auditTrail(long actionId) {
        return jdbc.sql("""
                SELECT event_type || ' ' || actor_type || COALESCE(' ' || actor_ref, '') FROM audit_events
                 WHERE action_id = ? ORDER BY occurred_at, audit_id""")
            .param(actionId)
            .query(String.class)
            .list();
    }
}
