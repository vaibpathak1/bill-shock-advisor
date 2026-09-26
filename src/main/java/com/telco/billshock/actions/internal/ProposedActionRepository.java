package com.telco.billshock.actions.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.telco.billshock.actions.ActionEffect;
import com.telco.billshock.actions.ActionStatus;
import com.telco.billshock.actions.ActionType;
import com.telco.billshock.actions.ExecutionStep;
import com.telco.billshock.actions.guardrail.CreditHistory;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.Money;

/**
 * {@code proposed_action} through {@code JdbcClient} (A-119). Every state change is a guarded
 * {@code UPDATE … WHERE status = ? AND version = ?} that also increments {@code version}; zero
 * rows means another request won, and the caller rolls back (actions.md §6.6 rule 4).
 */
@Repository
class ProposedActionRepository implements CreditHistory {

    private static final String COLUMNS = """
            action_id, account_id, conversation_id, action_type, status, amount, gst_amount, params,
            requires_supervisor, target_ref, execution, idempotency_key, expires_at, decided_by, decided_at,
            executed_at, external_ref, failure_reason, version, created_at""";

    private static final String OPEN_OR_BLOCKING = """
            (status IN ('PENDING_CONFIRMATION', 'AWAITING_SUPERVISOR', 'APPROVED', 'AUTO_APPROVED', 'EXECUTING',
                        'ESCALATED')
             OR (status = 'EXECUTED' AND action_type IN ('GOODWILL_CREDIT', 'VAS_UNSUBSCRIBE', 'THIRD_PARTY_BARRING',
                                                         'DISPUTE')))""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    ProposedActionRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** A new row, or empty if a live or blocking action holds the same target (unique index, §5.3). */
    record NewAction(AccountId account, UUID conversationId, ActionType type, ActionStatus status, Money amount,
            Money gstAmount, ActionParams params, String guardrailResultJson, boolean requiresSupervisor,
            String targetRef, Instant expiresAt, Instant createdAt) {
    }

    Optional<Long> insert(NewAction a) {
        Optional<Long> id = jdbc.sql("""
                INSERT INTO proposed_action (account_id, conversation_id, action_type, status, amount, gst_amount,
                                             params, guardrail_result, requires_supervisor, target_ref, expires_at,
                                             created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING action_id""")
            .params(a.account().value(), a.conversationId(), a.type().name(), a.status().name(),
                    a.amount() == null ? null : a.amount().amount(),
                    a.gstAmount() == null ? null : a.gstAmount().amount(), json.writeValueAsString(a.params()),
                    a.guardrailResultJson(), a.requiresSupervisor(), a.targetRef(), ts(a.expiresAt()),
                    ts(a.createdAt()))
            .query(Long.class)
            .optional();
        id.ifPresent(actionId -> jdbc.sql("UPDATE proposed_action SET idempotency_key = ? WHERE action_id = ?")
            .params("pa-" + actionId, actionId)
            .update());
        return id;
    }

    /** The live or blocking action that holds a target (the one an insert conflicted with). */
    Optional<ActionRow> findHolder(AccountId account, ActionType type, String targetRef) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM proposed_action WHERE account_id = ? AND action_type = ?"
                + " AND target_ref = ? AND " + OPEN_OR_BLOCKING + " ORDER BY action_id LIMIT 1")
            .params(account.value(), type.name(), targetRef)
            .query(this::row)
            .optional();
    }

    /** Live or executed disputes of the account, for the line-item overlap rule (§12, answer 3). */
    List<ActionRow> blockingDisputes(AccountId account) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM proposed_action WHERE account_id = ? AND action_type = 'DISPUTE'"
                + " AND " + OPEN_OR_BLOCKING)
            .param(account.value())
            .query(this::row)
            .list();
    }

    /** Scoped to the account: another account's id is simply not found (404). */
    Optional<ActionRow> find(AccountId account, long actionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM proposed_action WHERE action_id = ? AND account_id = ?")
            .params(actionId, account.value())
            .query(this::row)
            .optional();
    }

    /** Unscoped: only for the system re-drive (§6.3). */
    Optional<ActionRow> find(long actionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM proposed_action WHERE action_id = ?")
            .param(actionId)
            .query(this::row)
            .optional();
    }

    List<ActionRow> forConversation(AccountId account, UUID conversationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM proposed_action WHERE account_id = ? AND conversation_id = ?"
                + " ORDER BY action_id")
            .params(account.value(), conversationId)
            .query(this::row)
            .list();
    }

    List<ActionRow> page(AccountId account, ActionStatus status, int page, int size) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM proposed_action WHERE account_id = ?"
                + " AND (CAST(? AS text) IS NULL OR status = ?) ORDER BY created_at DESC, action_id DESC"
                + " LIMIT ? OFFSET ?")
            .params(account.value(), status == null ? null : status.name(), status == null ? null : status.name(),
                    size, (long) page * size)
            .query(this::row)
            .list();
    }

    long count(AccountId account, ActionStatus status) {
        return jdbc.sql("SELECT count(*) FROM proposed_action WHERE account_id = ?"
                + " AND (CAST(? AS text) IS NULL OR status = ?)")
            .params(account.value(), status == null ? null : status.name(), status == null ? null : status.name())
            .query(Long.class)
            .single();
    }

    /** Pending proposals of the account whose TTL has passed (A-111). */
    List<ActionRow> expiredPending(AccountId account, Instant now) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM proposed_action WHERE account_id = ?"
                + " AND status = 'PENDING_CONFIRMATION' AND expires_at < ? ORDER BY action_id")
            .params(account.value(), ts(now))
            .query(this::row)
            .list();
    }

    /**
     * Moves {@code row} from its current status to {@code to}; {@code false} if another request
     * changed it first. {@code decidedBy}/{@code decidedAt}/{@code failureReason} are kept when {@code null}.
     */
    boolean transition(ActionRow row, ActionStatus to, String decidedBy, Instant decidedAt, String failureReason) {
        return jdbc.sql("""
                UPDATE proposed_action
                   SET status = ?, version = version + 1, decided_by = COALESCE(?, decided_by),
                       decided_at = COALESCE(?, decided_at), failure_reason = COALESCE(?, failure_reason)
                 WHERE action_id = ? AND status = ? AND version = ?""")
            .params(to.name(), decidedBy, ts(decidedAt), failureReason, row.actionId(), row.status().name(),
                    row.version())
            .update() == 1;
    }

    /** The confirm-time re-check asked for a supervisor where the proposal did not (same transaction). */
    void requireSupervisor(long actionId) {
        jdbc.sql("UPDATE proposed_action SET requires_supervisor = true WHERE action_id = ?").param(actionId).update();
    }

    /** Claims a row for a re-drive or a ticket write without changing its status. */
    boolean claim(ActionRow row) {
        return jdbc.sql("UPDATE proposed_action SET version = version + 1"
                + " WHERE action_id = ? AND status = ? AND version = ?")
            .params(row.actionId(), row.status().name(), row.version())
            .update() == 1;
    }

    /**
     * Writes the execution result; {@code to} is {@code EXECUTED}, {@code FAILED} or {@code EXECUTING}
     * (unknown outcome). Guarded by the version the executor started from.
     */
    boolean finishExecution(long actionId, int version, ActionStatus from, ActionStatus to,
            List<ExecutionStep> steps, String externalRef, Instant executedAt, String failureReason) {
        return jdbc.sql("""
                UPDATE proposed_action
                   SET status = ?, version = version + 1, execution = ?::jsonb,
                       external_ref = COALESCE(?, external_ref), executed_at = COALESCE(?, executed_at),
                       failure_reason = COALESCE(?, failure_reason)
                 WHERE action_id = ? AND status = ? AND version = ?""")
            .params(to.name(), stepsJson(steps), externalRef, ts(executedAt), failureReason, actionId, from.name(),
                    version)
            .update() == 1;
    }

    @Override
    public boolean goodwillCreditSince(AccountId account, Instant from) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM proposed_action
                                WHERE account_id = ? AND action_type = 'GOODWILL_CREDIT'
                                  AND status IN ('EXECUTING', 'EXECUTED') AND decided_at >= ?)""")
            .params(account.value(), ts(from))
            .query(Boolean.class)
            .single();
    }

    String stepsJson(List<ExecutionStep> steps) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExecutionStep s : steps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("effect", s.effect().name());
            m.put("status", s.status().name());
            if (s.reference() != null) {
                m.put("reference", s.reference());
            }
            if (s.detail() != null) {
                m.put("detail", s.detail());
            }
            if (s.at() != null) {
                m.put("at", s.at().toString());
            }
            out.add(m);
        }
        return json.writeValueAsString(out);
    }

    private List<ExecutionStep> steps(String text) {
        List<ExecutionStep> out = new ArrayList<>();
        for (JsonNode n : json.readTree(text)) {
            out.add(new ExecutionStep(ActionEffect.valueOf(n.get("effect").asString()),
                    ExecutionStep.StepStatus.valueOf(n.get("status").asString()), text(n, "reference"),
                    text(n, "detail"), n.hasNonNull("at") ? Instant.parse(n.get("at").asString()) : null));
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asString() : null;
    }

    private ActionRow row(ResultSet rs, int n) throws SQLException {
        return new ActionRow(rs.getLong("action_id"), AccountId.of(rs.getLong("account_id")),
                rs.getObject("conversation_id", UUID.class), ActionType.valueOf(rs.getString("action_type")),
                ActionStatus.valueOf(rs.getString("status")), money(rs, "amount"), money(rs, "gst_amount"),
                json.readValue(rs.getString("params"), ActionParams.class), rs.getBoolean("requires_supervisor"),
                rs.getString("target_ref"), steps(rs.getString("execution")), rs.getString("idempotency_key"),
                instant(rs, "expires_at"), rs.getString("decided_by"), instant(rs, "decided_at"),
                instant(rs, "executed_at"), rs.getString("external_ref"), rs.getString("failure_reason"),
                rs.getInt("version"), instant(rs, "created_at"));
    }

    private static Money money(ResultSet rs, String column) throws SQLException {
        var value = rs.getBigDecimal(column);
        return value == null ? null : Money.of(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
