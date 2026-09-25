package com.telco.billshock.agent.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.telco.billshock.agent.CostProperties;
import com.telco.billshock.agent.LlmProperties;
import com.telco.billshock.agent.LlmProperties.Pricing;

/**
 * LLM cost per call in USD (llm-architecture.md §12; Q-17): Σ tokens × price per million
 * tokens, in {@link BigDecimal}, stored at scale 6 HALF_EVEN ({@code NUMERIC(14,6)}).
 * Anthropic reports uncached input, cache writes and cache reads as separate counts, so each
 * is priced once. Cache writes use the 5-minute rate, matching the chat TTL.
 */
@Component
class CostMeter {

    static final int USD_SCALE = 6;
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final LlmProperties llm;
    private final CostProperties cost;

    CostMeter(LlmProperties llm, CostProperties cost) {
        this.llm = llm;
        this.cost = cost;
    }

    BigDecimal costUsd(String model, long inputTokens, long cacheWriteTokens, long cacheReadTokens, long outputTokens) {
        Pricing p = llm.pricing().get(model);
        if (p == null) {
            throw new IllegalStateException("No price for model " + model);
        }
        BigDecimal micro = p.inputPerMtok().multiply(BigDecimal.valueOf(inputTokens))
            .add(p.cacheWrite5mPerMtok().multiply(BigDecimal.valueOf(cacheWriteTokens)))
            .add(p.cacheReadPerMtok().multiply(BigDecimal.valueOf(cacheReadTokens)))
            .add(p.outputPerMtok().multiply(BigDecimal.valueOf(outputTokens)));
        return micro.divide(MILLION, USD_SCALE, RoundingMode.HALF_EVEN);
    }

    /** NFR-09: at or above the per-conversation cap (INR at the planning FX rate, A-36)? */
    boolean capReached(BigDecimal conversationCostUsd) {
        return conversationCostUsd.multiply(cost.fxUsdInr()).compareTo(cost.maxPerConversationInr()) >= 0;
    }
}
