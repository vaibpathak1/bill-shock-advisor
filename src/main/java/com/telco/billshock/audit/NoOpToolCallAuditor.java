package com.telco.billshock.audit;

import java.util.UUID;

import org.springframework.stereotype.Component;

/** The Phase 5a placeholder for {@link ToolCallAuditor}; replaced by the audit writer in 6a. */
@Component
public class NoOpToolCallAuditor implements ToolCallAuditor {

    @Override
    public void toolCalled(UUID conversationId, String toolName, String maskedArguments, String resultSha256,
            Outcome outcome) {
        // Intentionally empty until 6a (5a answer Q-33).
    }
}
