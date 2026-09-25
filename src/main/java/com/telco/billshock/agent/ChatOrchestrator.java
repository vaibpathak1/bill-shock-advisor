package com.telco.billshock.agent;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * One chat turn: {@code diffBills} pre-fetch and summary, the tool loop with the per-turn budget,
 * the grounding gates, the deterministic fallback, memory, metering and the diagnosis
 * (llm-architecture.md §4; agent.md §4).
 */
public interface ChatOrchestrator {

    /**
     * Resolves the customer from the SecurityContext, scrubs the message, and opens a new
     * conversation or checks that the given one belongs to the customer.
     *
     * @param conversationId {@code null} to start a new conversation
     * @throws ConversationNotFoundException if the id is unknown or belongs to another account
     */
    ChatTurn start(UUID conversationId, String message);

    /**
     * Runs the turn. Must be called on a thread that carries the caller's SecurityContext. Events
     * are delivered in order; the last one is always {@link ChatEvent.Done} or {@link ChatEvent.Error}.
     */
    void run(ChatTurn turn, Consumer<ChatEvent> events);
}
