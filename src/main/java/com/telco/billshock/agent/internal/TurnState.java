package com.telco.billshock.agent.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.telco.billshock.domain.AccountId;

/**
 * State of one chat turn, handed to {@code recordDiagnosis} through the Spring AI
 * {@code ToolContext} (never sent to the model). Identity still comes from the
 * SecurityContext; the account here is only for checks. Not thread-safe: one turn, one thread.
 */
public final class TurnState {

    static final String KEY = "billshock.turn";

    private final UUID conversationId;
    private final AccountId account;
    private final List<String> toolsCalled = new ArrayList<>();
    private LlmDiagnosis recordedDiagnosis;

    TurnState(UUID conversationId, AccountId account) {
        this.conversationId = conversationId;
        this.account = account;
    }

    UUID conversationId() {
        return conversationId;
    }

    AccountId account() {
        return account;
    }

    void record(LlmDiagnosis diagnosis) {
        this.recordedDiagnosis = diagnosis;
    }

    LlmDiagnosis recordedDiagnosis() {
        return recordedDiagnosis;
    }

    void toolCalled(String name) {
        toolsCalled.add(name);
    }

    List<String> toolsCalled() {
        return List.copyOf(toolsCalled);
    }

    /** The model's {@code recordDiagnosis} input, before the diagnosis gate (agent.md §8.2). */
    record LlmDiagnosis(List<DiagnosisTool.CauseInput> causes, String totalExcess, String confidence,
            List<DiagnosisTool.ActionInput> recommendedActions) {
    }
}
