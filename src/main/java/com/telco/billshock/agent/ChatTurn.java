package com.telco.billshock.agent;

import java.util.UUID;

import com.telco.billshock.domain.AccountId;

/**
 * A validated turn, prepared on the request thread (identity, conversation ownership, scrubbed
 * text) before the SSE stream starts, so an unknown conversation is a plain HTTP 404.
 *
 * @param message the customer text after PII scrubbing (security.md §6.2)
 * @param newConversation {@code true} on the first turn: the pre-fetch runs (agent.md §4)
 */
public record ChatTurn(UUID conversationId, AccountId account, String message, boolean newConversation) {
}
