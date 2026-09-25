package com.telco.billshock.agent;

import java.util.UUID;

/** Unknown conversation, or one owned by another account: the two are indistinguishable (404). */
public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(UUID conversationId) {
        super("Conversation not found: " + conversationId);
    }
}
