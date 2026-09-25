package com.telco.billshock.agent;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One server-sent event of a chat turn (agent.md §4.1). {@link #name()} is the SSE event name;
 * the record is the JSON data.
 */
public sealed interface ChatEvent {

    String name();

    /** The deterministic spike summary, before the first LLM call (NFR-01a). */
    record Summary(UUID conversationId, String text) implements ChatEvent {
        public String name() {
            return "summary";
        }
    }

    /** Fixed wording per tool; never model text. */
    record Progress(String text) implements ChatEvent {
        public String name() {
            return "progress";
        }
    }

    /** One sentence that passed the grounding gate (5a answer Q-34). */
    record Token(String text) implements ChatEvent {
        public String name() {
            return "token";
        }
    }

    /** The client must discard the draft answer: a regeneration follows. */
    record Reset(String reason) implements ChatEvent {
        public String name() {
            return "reset";
        }
    }

    /** The deterministic template answer replaces the LLM answer. */
    record Fallback(String reason, String text) implements ChatEvent {
        public String name() {
            return "fallback";
        }
    }

    record Diagnosis(DiagnosisView diagnosis) implements ChatEvent {
        public String name() {
            return "diagnosis";
        }
    }

    record Error(String code) implements ChatEvent {
        public String name() {
            return "error";
        }
    }

    record Done(UUID conversationId, String promptVersion) implements ChatEvent {
        public String name() {
            return "done";
        }
    }

    /** The {@code BillShockDiagnosis} as shown to the customer: amounts as GST-labelled strings. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DiagnosisView(String billPeriod, String verdict, String totalExcess, List<CauseView> causes,
            String confidence, List<ActionView> recommendedActions, String source) {
    }

    record CauseView(String group, String amountInclGst, List<Long> lineItemIds) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ActionView(String type, String code, String summary) {
    }
}
