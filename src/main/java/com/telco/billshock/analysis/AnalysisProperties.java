package com.telco.billshock.analysis;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * Configuration of the deterministic engines (deterministic-core.md §2.4, §3.5). The chat
 * rule is separate from the proactive {@code billshock.anomaly.*} rule and shares no key
 * with it (A-83).
 */
@Validated
@ConfigurationProperties("billshock.analysis")
public record AnalysisProperties(@Valid @NotNull Chat chat, @Valid @NotNull Simulator simulator) {

    /**
     * The chat "meaningful increase" rule (Q-22): excess ≥ {@code minExcessInr} and ≥
     * {@code minExcessPct} of the baseline, both on GST-inclusive totals.
     */
    public record Chat(@NotNull @DecimalMin("0.00") BigDecimal minExcessInr,
            @NotNull @DecimalMin("0.00") BigDecimal minExcessPct) {
    }

    /** @param maxPlanOptions how many cheaper plans {@code simulatePlans} returns (Q-21) */
    public record Simulator(@Min(1) int maxPlanOptions) {
    }
}
