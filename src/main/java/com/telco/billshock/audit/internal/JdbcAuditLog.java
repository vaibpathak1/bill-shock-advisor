package com.telco.billshock.audit.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.telco.billshock.audit.AuditEvent;
import com.telco.billshock.audit.AuditEvent.ActorType;
import com.telco.billshock.audit.AuditLog;
import com.telco.billshock.audit.ToolCallAuditor;
import com.telco.billshock.domain.AccountId;

/**
 * The audit writer (actions.md §7). {@code JdbcClient} joins the caller's transaction when there
 * is one; a tool call outside a transaction is its own statement. {@code audit_events} rejects
 * UPDATE and DELETE (V5 trigger), so rows are only ever inserted.
 */
@Repository
class JdbcAuditLog implements AuditLog, ToolCallAuditor {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    JdbcAuditLog(JdbcClient jdbc, JsonMapper json, ObjectProvider<Clock> clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @Override
    public void record(AuditEvent e) {
        jdbc.sql("""
                INSERT INTO audit_events (occurred_at, account_id, conversation_id, correlation_id, actor_type,
                                          actor_ref, event_type, tool_name, action_id, prompt_version, payload,
                                          result_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)""")
            .params(Timestamp.from(clock.instant()), e.account() == null ? null : e.account().value(),
                    e.conversationId(), MDC.get("correlationId"), e.actor().name(), e.actorRef(), e.eventType(),
                    e.toolName(), e.actionId(), MDC.get("promptVersion"), e.payloadJson(), e.resultHash())
            .update();
    }

    @Override
    public void toolCalled(AccountId account, UUID conversationId, String toolName, String maskedArguments,
            String resultSha256, Outcome outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outcome", outcome.name());
        payload.put("arguments", arguments(maskedArguments));
        record(new AuditEvent(account, conversationId, ActorType.AGENT, null, "TOOL_CALLED", toolName, null,
                json.writeValueAsString(payload), resultSha256));
    }

    /** The arguments as JSON when they parse, else as a string. */
    private Object arguments(String maskedArguments) {
        if (maskedArguments == null || maskedArguments.isBlank()) {
            return Map.of();
        }
        try {
            return json.readTree(maskedArguments);
        }
        catch (JacksonException e) {
            return maskedArguments;
        }
    }
}
