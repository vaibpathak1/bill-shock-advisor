package com.telco.billshock.actions.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.telco.billshock.actions.ActionEffect;
import com.telco.billshock.actions.ActionExecution;
import com.telco.billshock.actions.ActionPage;
import com.telco.billshock.actions.ActionRequest;
import com.telco.billshock.actions.ActionStatus;
import com.telco.billshock.actions.ActionType;
import com.telco.billshock.actions.ActionView;
import com.telco.billshock.actions.ActionsProperties;
import com.telco.billshock.actions.CommandResponse;
import com.telco.billshock.actions.GuardrailDecision;
import com.telco.billshock.actions.GuardrailDecision.Outcome;
import com.telco.billshock.actions.GuardrailService;
import com.telco.billshock.actions.ProposalResult;
import com.telco.billshock.actions.ProposedActionService;
import com.telco.billshock.audit.AuditEvent;
import com.telco.billshock.audit.AuditEvent.ActorType;
import com.telco.billshock.audit.AuditLog;
import com.telco.billshock.bss.BillingReadModel;
import com.telco.billshock.bss.BillingReadModel.LineItem;
import com.telco.billshock.bss.ProductInventoryGateway;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.InrFormat;
import com.telco.billshock.security.PiiScrubber;

/**
 * The ProposedAction workflow (ADR-004 as amended; actions.md §2–§7).
 *
 * <p>Transactions: a state change and its audit rows commit together. <b>No BSS call runs inside
 * a transaction</b>: the confirm claims the idempotency key and moves the action to
 * {@code EXECUTING} in T1, calls the BSS, then records the result in T2 (§6.1). Every state change
 * is version-guarded, so two confirms cannot both execute (§6.6).
 */
@Service
class ActionWorkflow implements ProposedActionService, ActionExecution {

    static final String SYSTEM = "system";

    private static final int FREE_TEXT_MAX = 200;

    private final ProposedActionRepository actions;
    private final IdempotencyStore idempotency;
    private final GuardrailService guardrails;
    private final List<ActionExecutor> executors;
    private final TicketExecutor tickets;
    private final ExecutionRunner runner;
    private final AuditLog audit;
    private final BillingReadModel billing;
    private final ProductInventoryGateway inventory;
    private final ActionsProperties properties;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final Clock clock;

    ActionWorkflow(ProposedActionRepository actions, IdempotencyStore idempotency, GuardrailService guardrails,
            List<ActionExecutor> executors, TicketExecutor tickets, ExecutionRunner runner, AuditLog audit,
            BillingReadModel billing, ProductInventoryGateway inventory, ActionsProperties properties, JsonMapper json,
            PlatformTransactionManager transactions, ObjectProvider<Clock> clock) {
        this.actions = actions;
        this.idempotency = idempotency;
        this.guardrails = guardrails;
        this.executors = List.copyOf(executors);
        this.tickets = tickets;
        this.runner = runner;
        this.audit = audit;
        this.billing = billing;
        this.inventory = inventory;
        this.properties = properties;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    // ------------------------------------------------------------------ propose

    @Override
    public ProposalResult propose(AccountId account, UUID conversationId, ActionRequest request) {
        ActionType type = ActionType.of(request);
        GuardrailDecision decision = guardrails.evaluate(account, request);
        if (decision.outcome() == Outcome.REJECT) {
            audit.record(new AuditEvent(account, conversationId, ActorType.AGENT, null, "ACTION_NOT_POSSIBLE", null,
                    null, payload("type", type, "reasons", decision.reasons()), null));
            return new ProposalResult(ProposalResult.Kind.NOT_POSSIBLE, Optional.empty(), decision.reasons());
        }
        if (request instanceof ActionRequest.Dispute dispute) {
            // Owner answer 3: a dispute's target is its set of line items; an overlapping set is the same dispute.
            Optional<ActionRow> overlapping = actions.blockingDisputes(account)
                .stream()
                .filter(r -> r.params().lineItemIds() != null
                        && r.params().lineItemIds().stream().anyMatch(dispute.lineItemIds()::contains))
                .findFirst();
            if (overlapping.isPresent()) {
                return alreadyProposed(account, conversationId, overlapping.get(), decision);
            }
        }

        Instant now = clock.instant();
        ActionStatus status = decision.outcome() == Outcome.ESCALATE ? ActionStatus.ESCALATED
                : ActionStatus.PENDING_CONFIRMATION;
        String period = decision.billPeriod() == null ? null : decision.billPeriod().toString();
        ActionParams params = ActionParams.of(request, period, productName(account, request),
                categories(account, decision), freeText(reason(request)), freeText(summary(request)));
        var row = new ProposedActionRepository.NewAction(account, conversationId, type, status,
                decision.amountExclGst(), decision.gstAmount(), params, guardrailJson(decision),
                decision.supervisorRequired(), TargetRefs.of(request, decision, conversationId),
                status == ActionStatus.PENDING_CONFIRMATION ? now.plus(properties.pendingTtl()) : null, now);

        record Inserted(ActionRow row, boolean created) {
        }
        Inserted inserted = tx.execute(s -> {
            Optional<Long> id = actions.insert(row);
            if (id.isEmpty()) {
                return new Inserted(actions.findHolder(account, type, row.targetRef())
                    .orElseThrow(() -> new IllegalStateException("Target conflict without a holder")), false);
            }
            audit.record(AuditEvent.action(account, conversationId, ActorType.AGENT, null,
                    status == ActionStatus.ESCALATED ? "ACTION_ESCALATED" : "ACTION_PROPOSED", id.get(),
                    guardrailJson(decision)));
            return new Inserted(actions.find(id.get()).orElseThrow(), true);
        });
        if (!inserted.created()) {
            return alreadyProposed(account, conversationId, inserted.row(), decision);
        }
        if (status == ActionStatus.ESCALATED) {
            return new ProposalResult(ProposalResult.Kind.ESCALATED, Optional.of(ActionViews.view(openTicket(inserted.row()))),
                    decision.reasons());
        }
        return new ProposalResult(ProposalResult.Kind.PROPOSED, Optional.of(ActionViews.view(inserted.row())),
                decision.reasons());
    }

    private ProposalResult alreadyProposed(AccountId account, UUID conversationId, ActionRow holder,
            GuardrailDecision decision) {
        audit.record(AuditEvent.action(account, conversationId, ActorType.AGENT, null, "ACTION_ALREADY_PROPOSED",
                holder.actionId(), "{}"));
        return new ProposalResult(ProposalResult.Kind.ALREADY_PROPOSED, Optional.of(ActionViews.view(holder)),
                decision.reasons());
    }

    // ------------------------------------------------------------------ confirm / reject

    @Override
    public CommandResponse confirm(AccountId account, String actorRef, long actionId, String key, String requestHash) {
        Instant now = clock.instant();
        Step first;
        try {
            first = tx.execute(s -> {
                Optional<IdempotencyStore.Stored> prior = idempotency.claim(account, key, requestHash);
                if (prior.isPresent()) {
                    return Step.replay(prior.get());
                }
                Optional<ActionRow> found = actions.find(account, actionId);
                if (found.isEmpty()) {
                    return Step.done(idempotency.complete(account, key, notFound()));
                }
                ActionRow a = found.get();
                Optional<CommandResponse> unavailable = expiredOrNotPending(a, now);
                if (unavailable.isPresent()) {
                    return Step.done(idempotency.complete(account, key, unavailable.get()));
                }

                GuardrailDecision d = guardrails.evaluate(account, a.params().toRequest(a.type()));
                audit.record(AuditEvent.action(account, a.conversationId(), ActorType.SYSTEM, null,
                        "GUARDRAIL_RECHECKED", a.actionId(), guardrailJson(d)));
                audit.record(AuditEvent.action(account, a.conversationId(), ActorType.CUSTOMER, actorRef,
                        "ACTION_CONFIRMED", a.actionId(), "{}"));

                boolean amountChanged = d.outcome() != Outcome.REJECT
                        && (!Objects.equals(a.amount(), d.amountExclGst()) || !Objects.equals(a.gstAmount(), d.gstAmount()));
                if (d.outcome() == Outcome.REJECT || amountChanged) {
                    String reason = amountChanged ? ActionViews.AMOUNT_CHANGED : ActionViews.GUARDRAIL_REJECTED;
                    transition(a, ActionStatus.REJECTED, SYSTEM, now, reason);
                    audit.record(AuditEvent.action(account, a.conversationId(), ActorType.SYSTEM, null,
                            "ACTION_REJECTED", a.actionId(), payload("reason", reason)));
                    ActionRow rejected = actions.find(a.actionId()).orElseThrow();
                    return Step.done(idempotency.complete(account, key, noLongerValid(rejected, reason, d)));
                }
                if (d.outcome() == Outcome.ESCALATE) {
                    transition(a, ActionStatus.ESCALATED, actorRef, now, null);
                    audit.record(AuditEvent.action(account, a.conversationId(), ActorType.SYSTEM, null,
                            "ACTION_ESCALATED", a.actionId(), guardrailJson(d)));
                    return Step.ticket(actions.find(a.actionId()).orElseThrow());
                }
                if (d.supervisorRequired()) {
                    transition(a, ActionStatus.AWAITING_SUPERVISOR, actorRef, now, null);
                    actions.requireSupervisor(a.actionId());
                    audit.record(AuditEvent.action(account, a.conversationId(), ActorType.SYSTEM, null,
                            "ACTION_AWAITING_SUPERVISOR", a.actionId(), "{}"));
                    ActionRow waiting = actions.find(a.actionId()).orElseThrow();
                    return Step.done(idempotency.complete(account, key, ok(200, waiting)));
                }
                // APPROVED and EXECUTING in one guarded update: nothing rests in APPROVED in 6a (§6.1).
                transition(a, ActionStatus.EXECUTING, actorRef, now, null);
                audit.record(AuditEvent.action(account, a.conversationId(), ActorType.SYSTEM, null, "ACTION_APPROVED",
                        a.actionId(), "{}"));
                audit.record(AuditEvent.action(account, a.conversationId(), ActorType.SYSTEM, null,
                        "ACTION_EXECUTING", a.actionId(), "{}"));
                return Step.execute(actions.find(a.actionId()).orElseThrow());
            });
        }
        catch (ConcurrentChangeException e) {
            // T1 rolled back, the key claim with it: another request moved the action first.
            return notPending();
        }

        return switch (first.kind()) {
            case REPLAY -> replay(first.stored(), requestHash);
            case DONE -> first.response();
            case TICKET -> {
                ActionRow escalated = openTicket(first.row());
                yield tx.execute(s -> idempotency.complete(account, key, ok(200, escalated)));
            }
            case EXECUTE -> {
                ActionRow result = execute(first.row());
                int status = result.status() == ActionStatus.EXECUTING ? 202 : 200;
                yield tx.execute(s -> idempotency.complete(account, key, ok(status, result)));
            }
        };
    }

    @Override
    public CommandResponse reject(AccountId account, String actorRef, long actionId, String key, String requestHash,
            String reason) {
        Instant now = clock.instant();
        try {
            return tx.execute(s -> {
                Optional<IdempotencyStore.Stored> prior = idempotency.claim(account, key, requestHash);
                if (prior.isPresent()) {
                    return replay(prior.get(), requestHash);
                }
                Optional<ActionRow> found = actions.find(account, actionId);
                if (found.isEmpty()) {
                    return idempotency.complete(account, key, notFound());
                }
                ActionRow a = found.get();
                Optional<CommandResponse> unavailable = expiredOrNotPending(a, now);
                if (unavailable.isPresent()) {
                    return idempotency.complete(account, key, unavailable.get());
                }
                transition(a, ActionStatus.REJECTED, actorRef, now, null);
                audit.record(AuditEvent.action(account, a.conversationId(), ActorType.CUSTOMER, actorRef,
                        "ACTION_REJECTED", a.actionId(), payload("reason", freeText(reason))));
                return idempotency.complete(account, key, ok(200, actions.find(a.actionId()).orElseThrow()));
            });
        }
        catch (ConcurrentChangeException e) {
            return notPending();
        }
    }

    /** {@code PENDING_CONFIRMATION} past its TTL becomes {@code EXPIRED} (A-111); anything else not pending is 409. */
    private Optional<CommandResponse> expiredOrNotPending(ActionRow a, Instant now) {
        if (a.status() == ActionStatus.PENDING_CONFIRMATION && a.expiresAt() != null && a.expiresAt().isBefore(now)) {
            expire(a, now);
            return Optional.of(Problems.response(json, 409, "ACTION_EXPIRED", "Proposal expired",
                    ActionViews.message(actions.find(a.actionId()).orElseThrow()), Map.of()));
        }
        if (a.status() != ActionStatus.PENDING_CONFIRMATION) {
            return Optional.of(Problems.response(json, 409, "ACTION_NOT_PENDING", "Action is not waiting for confirmation",
                    ActionViews.message(a), Map.of("status", a.status().name())));
        }
        return Optional.empty();
    }

    private CommandResponse replay(IdempotencyStore.Stored stored, String requestHash) {
        if (!stored.requestHash().equals(requestHash)) {
            return Problems.response(json, 422, "IDEMPOTENCY_KEY_REUSED", "Idempotency key reused",
                    "This Idempotency-Key was already used for a different request.", Map.of());
        }
        if (stored.inProgress()) {
            return Problems.response(json, 409, "REQUEST_IN_PROGRESS", "Request in progress",
                    "The first request with this Idempotency-Key is still running.", Map.of());
        }
        return stored.response();
    }

    private CommandResponse notFound() {
        return Problems.response(json, 404, "ACTION_NOT_FOUND", "Action not found", "No such action.", Map.of());
    }

    private CommandResponse notPending() {
        return Problems.response(json, 409, "ACTION_NOT_PENDING", "Action is not waiting for confirmation",
                "Another request changed this action first.", Map.of());
    }

    /** Owner answer 4: say the amount changed, show both amounts, offer a fresh proposal. */
    private CommandResponse noLongerValid(ActionRow rejected, String reason, GuardrailDecision now) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("reason", reason);
        if (rejected.amountInclGst() != null) {
            extra.put("proposedAmount", InrFormat.inclGst(rejected.amountInclGst()));
        }
        if (ActionViews.AMOUNT_CHANGED.equals(reason) && now.amountInclGst() != null) {
            extra.put("currentAmount", InrFormat.inclGst(now.amountInclGst()));
        }
        extra.put("action", ActionViews.view(rejected));
        return Problems.response(json, 409, "ACTION_NO_LONGER_VALID", "Action no longer valid",
                ActionViews.message(rejected), extra);
    }

    private CommandResponse ok(int status, ActionRow row) {
        return new CommandResponse(status, json.writeValueAsString(ActionViews.view(row)), false);
    }

    // ------------------------------------------------------------------ execution

    /** Runs the steps outside any transaction, then records the result (T2). */
    private ActionRow execute(ActionRow a) {
        ActionExecutor executor = executors.stream()
            .filter(e -> e.supports(a.type()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No executor for " + a.type()));
        ExecutionRunner.Run run = runner.run(a.actionId(), executor.steps(a));
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> {
            String event;
            boolean written = switch (run.outcome()) {
                case ALL_DONE -> {
                    event = "ACTION_EXECUTED";
                    yield actions.finishExecution(a.actionId(), a.version(), ActionStatus.EXECUTING,
                            ActionStatus.EXECUTED, run.steps(), run.references(), now, null);
                }
                case REJECTED -> {
                    event = "ACTION_FAILED";
                    yield actions.finishExecution(a.actionId(), a.version(), ActionStatus.EXECUTING, ActionStatus.FAILED,
                            run.steps(), run.references(), null, "BSS_REJECTED:" + run.rejectionCode());
                }
                case UNKNOWN -> {
                    // A-112: never FAILED on an unknown outcome; the effect may exist. Re-driven with the same key.
                    event = "ACTION_OUTCOME_UNKNOWN";
                    yield actions.finishExecution(a.actionId(), a.version(), ActionStatus.EXECUTING,
                            ActionStatus.EXECUTING, run.steps(), run.references(), null, null);
                }
            };
            if (written) {
                audit.record(AuditEvent.action(a.account(), a.conversationId(), ActorType.SYSTEM, null, event,
                        a.actionId(), stepsPayload(run)));
            }
        });
        return actions.find(a.actionId()).orElseThrow();
    }

    /** Opens the hand-off ticket of an {@code ESCALATED} action, without confirmation (§6.5). */
    private ActionRow openTicket(ActionRow a) {
        ExecutionRunner.Run run = runner.run(a.actionId(), tickets.escalation(a));
        tx.executeWithoutResult(s -> {
            if (actions.finishExecution(a.actionId(), a.version(), ActionStatus.ESCALATED, ActionStatus.ESCALATED,
                    run.steps(), run.outcome() == ExecutionRunner.Outcome.ALL_DONE ? run.references() : null, null,
                    null)) {
                audit.record(AuditEvent.action(a.account(), a.conversationId(), ActorType.SYSTEM, null,
                        run.outcome() == ExecutionRunner.Outcome.ALL_DONE ? "TICKET_OPENED" : "TICKET_NOT_OPENED",
                        a.actionId(), stepsPayload(run)));
            }
        });
        return actions.find(a.actionId()).orElseThrow();
    }

    @Override
    public ActionView redrive(long actionId) {
        ActionRow a = actions.find(actionId).orElseThrow(() -> new IllegalStateException("No action " + actionId));
        boolean ticketMissing = a.status() == ActionStatus.ESCALATED && !a.stepDone(ActionEffect.ESCALATION);
        if (a.status() != ActionStatus.EXECUTING && !ticketMissing) {
            throw new IllegalStateException("Action " + actionId + " is " + a.status() + "; only EXECUTING actions and"
                    + " escalations without a ticket are re-driven");
        }
        // Claim it: of two concurrent re-drives, only one calls the BSS.
        Boolean claimed = tx.execute(s -> {
            if (!actions.claim(a)) {
                return false;
            }
            audit.record(AuditEvent.action(a.account(), a.conversationId(), ActorType.SYSTEM, null, "ACTION_REDRIVEN",
                    a.actionId(), "{}"));
            return true;
        });
        ActionRow current = actions.find(actionId).orElseThrow();
        if (!Boolean.TRUE.equals(claimed)) {
            return ActionViews.view(current);
        }
        return ActionViews.view(ticketMissing ? openTicket(current) : execute(current));
    }

    // ------------------------------------------------------------------ queries

    @Override
    public ActionPage list(AccountId account, ActionStatus status, int page, int size) {
        return tx.execute(s -> {
            expireAll(account);
            List<ActionView> items = actions.page(account, status, page, size).stream().map(ActionViews::view).toList();
            return new ActionPage(items, page, size, actions.count(account, status));
        });
    }

    @Override
    public List<ActionView> forConversation(AccountId account, UUID conversationId) {
        return tx.execute(s -> {
            expireAll(account);
            return actions.forConversation(account, conversationId).stream().map(ActionViews::view).toList();
        });
    }

    @Override
    public Optional<ActionView> find(AccountId account, long actionId) {
        return actions.find(account, actionId).map(ActionViews::view);
    }

    private void expireAll(AccountId account) {
        Instant now = clock.instant();
        for (ActionRow a : actions.expiredPending(account, now)) {
            if (actions.transition(a, ActionStatus.EXPIRED, SYSTEM, now, null)) {
                audit.record(AuditEvent.action(account, a.conversationId(), ActorType.SYSTEM, null, "ACTION_EXPIRED",
                        a.actionId(), "{}"));
            }
        }
    }

    private void expire(ActionRow a, Instant now) {
        transition(a, ActionStatus.EXPIRED, SYSTEM, now, null);
        audit.record(AuditEvent.action(a.account(), a.conversationId(), ActorType.SYSTEM, null, "ACTION_EXPIRED",
                a.actionId(), "{}"));
    }

    private void transition(ActionRow a, ActionStatus to, String decidedBy, Instant decidedAt, String reason) {
        if (!actions.transition(a, to, decidedBy, decidedAt, reason)) {
            throw new ConcurrentChangeException();
        }
    }

    // ------------------------------------------------------------------ helpers

    private String productName(AccountId account, ActionRequest request) {
        if (!(request instanceof ActionRequest.VasUnsubscribe vas)) {
            return null;
        }
        return inventory.activeProducts(account)
            .stream()
            .filter(p -> p.subscriptionId().equals(vas.subscriptionId()))
            .map(p -> ActionViews.name(p.name()))
            .findFirst()
            .orElse(null);
    }

    /** Categories of the cited line items, from the read model (server data), for the summary. */
    private List<String> categories(AccountId account, GuardrailDecision decision) {
        if (decision.billPeriod() == null || decision.lineItemIds().isEmpty()) {
            return null;
        }
        Set<Long> ids = new HashSet<>(decision.lineItemIds());
        Set<String> categories = new LinkedHashSet<>();
        for (LineItem l : billing.lineItems(account, decision.billPeriod())) {
            if (ids.contains(l.lineItemId())) {
                categories.add(l.category());
            }
        }
        return categories.isEmpty() ? null : new ArrayList<>(categories);
    }

    private static String reason(ActionRequest request) {
        return switch (request) {
            case ActionRequest.GoodwillCredit c -> c.reason();
            case ActionRequest.Dispute d -> d.reason();
            case ActionRequest.Escalation e -> e.reason();
            default -> null;
        };
    }

    private static String summary(ActionRequest request) {
        return request instanceof ActionRequest.Escalation e ? e.summary() : null;
    }

    /** Model or customer free text: PII-scrubbed, control characters removed, 200 characters (actions.md §5.1). */
    static String freeText(String text) {
        if (text == null) {
            return null;
        }
        String clean = PiiScrubber.scrub(text).replaceAll("\\p{Cntrl}", " ").strip();
        return clean.length() > FREE_TEXT_MAX ? clean.substring(0, FREE_TEXT_MAX) : clean;
    }

    private String guardrailJson(GuardrailDecision d) {
        return payload("outcome", d.outcome(), "reasons", d.reasons(), "supervisorRequired", d.supervisorRequired(),
                "refundAmountIncomplete", d.refundAmountIncomplete());
    }

    private String stepsPayload(ExecutionRunner.Run run) {
        return payload("outcome", run.outcome(), "steps", run.steps()
            .stream()
            .map(st -> st.effect() + ":" + st.status() + (st.detail() == null ? "" : ":" + st.detail()))
            .collect(Collectors.toList()));
    }

    private String payload(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object v = keyValues[i + 1];
            map.put((String) keyValues[i], v instanceof Enum<?> e ? e.name() : v);
        }
        return json.writeValueAsString(map);
    }

    /** What T1 of a confirm decided. */
    private record Step(Kind kind, IdempotencyStore.Stored stored, CommandResponse response, ActionRow row) {

        enum Kind {
            REPLAY, DONE, TICKET, EXECUTE
        }

        static Step replay(IdempotencyStore.Stored stored) {
            return new Step(Kind.REPLAY, stored, null, null);
        }

        static Step done(CommandResponse response) {
            return new Step(Kind.DONE, null, response, null);
        }

        static Step ticket(ActionRow row) {
            return new Step(Kind.TICKET, null, null, row);
        }

        static Step execute(ActionRow row) {
            return new Step(Kind.EXECUTE, null, null, row);
        }
    }

    /** A guarded update found another version: roll back the whole transaction. */
    private static final class ConcurrentChangeException extends RuntimeException {

        ConcurrentChangeException() {
            super(null, null, false, false);
        }
    }
}
