package com.telco.billshock.agent;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM settings (llm-architecture.md §1, §9, §12; agent.md §3). Model ids and prices are
 * configuration, never code (Q-15, K-Q2).
 *
 * @param mode {@code LLM}, or {@code TEMPLATE_ONLY}: the global kill switch (ADR-005)
 * @param pricing USD per million tokens, per model id (A-20); a chat model without an entry fails at startup
 */
@ConfigurationProperties("billshock.llm")
public record LlmProperties(Mode mode, Chat chat, Prompt prompt, Map<String, Pricing> pricing) {

    public enum Mode {
        LLM, TEMPLATE_ONLY
    }

    public LlmProperties {
        mode = Objects.requireNonNullElse(mode, Mode.LLM);
        Objects.requireNonNull(chat, "billshock.llm.chat");
        Objects.requireNonNull(prompt, "billshock.llm.prompt");
        pricing = pricing == null ? Map.of() : Map.copyOf(pricing);
        if (!pricing.containsKey(chat.model())) {
            throw new IllegalStateException("No price for chat model " + chat.model() + " (billshock.llm.pricing)");
        }
    }

    /**
     * @param apiKey {@code ANTHROPIC_API_KEY_CHAT} (chat workspace); required in {@code LLM} mode
     * @param baseUrl only for tests (fake API); {@code null} = Anthropic
     * @param temperature {@code null} = omitted from the request (Q-1: Sonnet 5 rejects it)
     * @param effort {@code low|medium|high|xhigh|max}, or {@code null} for the model default (T-6)
     * @param thinking {@code adaptive}, {@code disabled}, or {@code null} for the model default
     * @param maxRoundTrips LLM calls per turn (llm-architecture.md §9)
     * @param maxInputTokensPerCall input + cache tokens of one call; above it the turn ends with the template
     * @param memoryWindow stored messages sent back to the model
     */
    public record Chat(String model, int maxTokens, Duration timeout, String apiKey, String baseUrl,
            Double temperature, String effort, String thinking, int maxRoundTrips, int maxInputTokensPerCall,
            int memoryWindow) {

        public Chat {
            Objects.requireNonNull(model, "billshock.llm.chat.model");
            Objects.requireNonNull(timeout, "billshock.llm.chat.timeout");
        }
    }

    /**
     * @param version prompt file version: {@code prompts/system.<version>.st}; rollback = config (SPEC §8.3)
     * @param canary a marker placed in the system prompt; output containing it is blocked (llm-architecture.md §7)
     */
    public record Prompt(String version, String locale, String canary) {
    }

    /** USD per million tokens. Cache writes use the 5-minute rate (the chat TTL is 5 minutes). */
    public record Pricing(BigDecimal inputPerMtok, BigDecimal cacheWrite5mPerMtok, BigDecimal cacheReadPerMtok,
            BigDecimal outputPerMtok) {

        public Pricing {
            Objects.requireNonNull(inputPerMtok, "input-per-mtok");
            Objects.requireNonNull(cacheWrite5mPerMtok, "cache-write-5m-per-mtok");
            Objects.requireNonNull(cacheReadPerMtok, "cache-read-per-mtok");
            Objects.requireNonNull(outputPerMtok, "output-per-mtok");
        }
    }
}
