package com.telco.billshock.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.telco.billshock.agent.CostProperties;
import com.telco.billshock.agent.LlmProperties;

/** Cost metering in BigDecimal, NUMERIC(14,6) USD (Q-17; llm-architecture.md §12). */
class CostMeterTest {

    static LlmProperties llm() {
        return new LlmProperties(LlmProperties.Mode.LLM,
                new LlmProperties.Chat("claude-sonnet-5", 1024, Duration.ofSeconds(30), "k", null, null, null, null, 9,
                        30000, 12),
                new LlmProperties.Prompt("v1", "en-IN", "BSA-REF-TEST"),
                Map.of("claude-sonnet-5", new LlmProperties.Pricing(new BigDecimal("2.00"), new BigDecimal("2.50"),
                        new BigDecimal("0.20"), new BigDecimal("10.00"))));
    }

    private final CostMeter meter = new CostMeter(llm(),
            new CostProperties(new BigDecimal("88"), new BigDecimal("45.00")));

    @Test
    void pricesEachTokenKindOnceAtItsRate() {
        // 12,000 input · $2 + 3,000 cache write · $2.50 + 9,000 cache read · $0.20 + 250 output · $10, per MTok
        assertThat(meter.costUsd("claude-sonnet-5", 12_000, 3_000, 9_000, 250)).isEqualTo(new BigDecimal("0.035800"));
    }

    @Test
    void roundsHalfEvenToSixDecimals() {
        // 1 input token = $0.000002; 1 cache-read token = $0.0000002 → 0.000000 (half-even of 0.0000002)
        assertThat(meter.costUsd("claude-sonnet-5", 1, 0, 0, 0)).isEqualTo(new BigDecimal("0.000002"));
        assertThat(meter.costUsd("claude-sonnet-5", 0, 0, 1, 0)).isEqualTo(new BigDecimal("0.000000"));
        assertThat(meter.costUsd("claude-sonnet-5", 0, 0, 3, 0)).isEqualTo(new BigDecimal("0.000001"));
    }

    @Test
    void theCapIsInclusiveAtTheFxRate() {
        // ₹45.00 ÷ 88 = $0.511364 (rounded); the cap compares USD × 88 against ₹45.00
        assertThat(meter.capReached(new BigDecimal("0.511363"))).isFalse();
        assertThat(meter.capReached(new BigDecimal("0.511364"))).isTrue();
    }

    @Test
    void aModelWithoutPriceIsRejected() {
        assertThatThrownBy(() -> meter.costUsd("claude-unknown", 1, 0, 0, 0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LlmProperties(LlmProperties.Mode.LLM, llm().chat(), llm().prompt(), Map.of()))
            .hasMessageContaining("No price for chat model");
    }
}
