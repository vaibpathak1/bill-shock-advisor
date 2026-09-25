package com.telco.billshock.agent;

import java.math.BigDecimal;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cost controls (NFR-09, C-9; llm-architecture.md §9, §12).
 *
 * @param fxUsdInr planning FX rate (A-36); used only for the per-conversation cap and dashboards
 * @param maxPerConversationInr at or above it, the conversation continues with template answers
 */
@ConfigurationProperties("billshock.cost")
public record CostProperties(BigDecimal fxUsdInr, BigDecimal maxPerConversationInr) {

    public CostProperties {
        Objects.requireNonNull(fxUsdInr, "billshock.cost.fx-usd-inr");
        Objects.requireNonNull(maxPerConversationInr, "billshock.cost.max-per-conversation-inr");
    }
}
