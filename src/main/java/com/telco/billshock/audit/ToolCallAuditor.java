package com.telco.billshock.audit;

import java.util.UUID;

/**
 * Records every tool call of a chat turn (SPEC §4.3 rule 5; llm-architecture.md §6). The hook
 * point is wired in 5a (5a answer Q-33); the implementation that writes {@code audit_events}
 * arrives in 6a. Until then {@link NoOpToolCallAuditor} is the bean.
 */
public interface ToolCallAuditor {

    /**
     * @param maskedArguments the tool arguments as sent by the model; they never carry identity
     * @param resultSha256 hex SHA-256 of the result returned to the model
     */
    void toolCalled(UUID conversationId, String toolName, String maskedArguments, String resultSha256,
            Outcome outcome);

    enum Outcome {
        EXECUTED, REFUSED_ALREADY_CALLED, REFUSED_BUDGET
    }
}
