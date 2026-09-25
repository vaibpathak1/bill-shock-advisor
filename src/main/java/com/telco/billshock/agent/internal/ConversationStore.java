package com.telco.billshock.agent.internal;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.UuidV7;

/**
 * Conversations, append-only chat memory, LLM call metering and diagnoses (V4; the agent
 * module owns these tables). Every query on {@code chat_messages} and {@code llm_call_log}
 * carries the partition key, derived from the UUIDv7 conversation id or the call time
 * (llm-architecture.md §8). Nothing is ever deleted and re-inserted (A-64; agent.md F-18).
 */
@Repository
class ConversationStore {

    enum Role {
        USER, ASSISTANT, SYSTEM_CONTEXT
    }

    record Conversation(UUID conversationId, AccountId account, BigDecimal llmCostUsd, String status) {
    }

    record StoredMessage(int seq, Role role, String content) {
    }

    /** One LLM round trip (llm-architecture.md §12). */
    record LlmCall(UUID conversationId, Instant calledAt, String model, String promptVersion, int inputTokens,
            int cacheWriteTokens, int cacheReadTokens, int outputTokens, BigDecimal costUsd, int latencyMs,
            String providerRequestId, String outcome) {
    }

    private final JdbcClient jdbc;

    ConversationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void create(UUID conversationId, AccountId account, String promptVersion, String model, int autonomyLevel) {
        jdbc.sql("""
                INSERT INTO conversation (conversation_id, account_id, channel, started_at, prompt_version, model,
                                          autonomy_level, status)
                VALUES (?, ?, 'WEB', ?, ?, ?, ?, 'OPEN')""")
            .params(conversationId, account.value(), Timestamp.from(UuidV7.timestamp(conversationId)), promptVersion,
                    model, autonomyLevel)
            .update();
    }

    /** The conversation, whoever owns it; callers compare the account themselves. */
    Optional<Conversation> find(UUID conversationId) {
        return jdbc.sql("SELECT account_id, llm_cost_usd, status FROM conversation WHERE conversation_id = ?")
            .param(conversationId)
            .query((rs, n) -> new Conversation(conversationId, AccountId.of(rs.getLong(1)), rs.getBigDecimal(2),
                    rs.getString(3)))
            .optional();
    }

    /** Appends one message with the next sequence number of the conversation. */
    void append(UUID conversationId, AccountId account, Role role, String content) {
        LocalDate month = UuidV7.month(conversationId);
        jdbc.sql("""
                INSERT INTO chat_messages (conversation_month, conversation_id, seq, account_id, role, content)
                SELECT ?, ?, COALESCE(MAX(seq) + 1, 0), ?, ?, ?
                  FROM chat_messages
                 WHERE conversation_id = ? AND conversation_month = ?""")
            .params(Date.valueOf(month), conversationId, account.value(), role.name(), content, conversationId,
                    Date.valueOf(month))
            .update();
    }

    /** The last {@code limit} messages, oldest first. */
    List<StoredMessage> window(UUID conversationId, int limit) {
        LocalDate month = UuidV7.month(conversationId);
        return jdbc.sql("""
                SELECT seq, role, content FROM (
                    SELECT seq, role, content FROM chat_messages
                     WHERE conversation_id = ? AND conversation_month = ?
                     ORDER BY seq DESC LIMIT ?) last
                 ORDER BY seq""")
            .params(conversationId, Date.valueOf(month), limit)
            .query((rs, n) -> new StoredMessage(rs.getInt(1), Role.valueOf(rs.getString(2)), rs.getString(3)))
            .list();
    }

    Optional<StoredMessage> message(UUID conversationId, int seq) {
        return jdbc.sql("""
                SELECT seq, role, content FROM chat_messages
                 WHERE conversation_id = ? AND conversation_month = ? AND seq = ?""")
            .params(conversationId, Date.valueOf(UuidV7.month(conversationId)), seq)
            .query((rs, n) -> new StoredMessage(rs.getInt(1), Role.valueOf(rs.getString(2)), rs.getString(3)))
            .optional();
    }

    boolean hasMessages(UUID conversationId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat_messages WHERE conversation_id = ? AND conversation_month = ?)")
            .params(conversationId, Date.valueOf(UuidV7.month(conversationId)))
            .query(Boolean.class)
            .single();
    }

    /** Logs one call and adds its cost to the conversation, in one transaction. */
    @Transactional
    void logCall(LlmCall call) {
        LocalDate month = LocalDate.ofInstant(call.calledAt(), ZoneOffset.UTC).withDayOfMonth(1);
        jdbc.sql("""
                INSERT INTO llm_call_log (called_month, called_at, conversation_id, model, prompt_version,
                                          input_tokens, cache_write_tokens, cache_read_tokens, output_tokens,
                                          cost_usd, latency_ms, provider_request_id, outcome)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(Date.valueOf(month), Timestamp.from(call.calledAt()), call.conversationId(), call.model(),
                    call.promptVersion(), call.inputTokens(), call.cacheWriteTokens(), call.cacheReadTokens(),
                    call.outputTokens(), call.costUsd(), call.latencyMs(), call.providerRequestId(), call.outcome())
            .update();
        if (call.conversationId() != null && call.costUsd().signum() > 0) {
            jdbc.sql("UPDATE conversation SET llm_cost_usd = llm_cost_usd + ? WHERE conversation_id = ?")
                .params(call.costUsd(), call.conversationId())
                .update();
        }
    }

    void saveDiagnosis(UUID diagnosisId, UUID conversationId, AccountId account, LocalDate billPeriod, String verdict,
            BigDecimal totalExcess, String causesJson, String confidence, String actionsJson, String source,
            String promptVersion) {
        jdbc.sql("""
                INSERT INTO bill_diagnosis (diagnosis_id, conversation_id, account_id, bill_period, verdict,
                                            total_excess, causes, confidence, recommended_actions, source,
                                            prompt_version)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), ?, ?)""")
            .params(diagnosisId, conversationId, account.value(), Date.valueOf(billPeriod), verdict, totalExcess,
                    causesJson, confidence, actionsJson, source, promptVersion)
            .update();
    }
}
