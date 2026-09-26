package com.telco.billshock.audit;

import java.util.UUID;

import com.telco.billshock.domain.AccountId;

/**
 * Records every tool call of a chat turn (SPEC §4.3 rule 5; llm-architecture.md §6). The hook
 * point was wired in 5a (answer Q-33); {@code JdbcAuditLog} writes it to {@code audit_events}
 * (6a, actions.md §7).
 */
public interface ToolCallAuditor {

    /**
     * @param account the turn's account, from the SecurityContext
     * @param maskedArguments the tool arguments as sent by the model, PII-scrubbed; they never carry identity
     * @param resultSha256 hex SHA-256 of the result returned to the model
     */
    void toolCalled(AccountId account, UUID conversationId, String toolName, String maskedArguments,
            String resultSha256, Outcome outcome);

    enum Outcome {
        EXECUTED, REFUSED_ALREADY_CALLED, REFUSED_BUDGET,
        /** The arguments were not grounded in tool results (goodwill amount, A-110); the tool was not run. */
        REFUSED_UNGROUNDED_ARGUMENT
    }
}
