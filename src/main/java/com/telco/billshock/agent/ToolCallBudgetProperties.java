package com.telco.billshock.agent;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Set;

/**
 * Tool calls per turn (SPEC §4.5; llm-architecture.md §9; deterministic-core.md §5).
 *
 * @param maxCountedPerTurn calls to tools not in {@code oncePerTurn}; the next one stops the turn
 * @param oncePerTurn tools excluded from the count and allowed at most once per turn
 */
@Validated
@ConfigurationProperties("billshock.agent.tool-calls")
public record ToolCallBudgetProperties(@Min(1) int maxCountedPerTurn, @NotNull Set<String> oncePerTurn) {

    public ToolCallBudgetProperties {
        oncePerTurn = oncePerTurn == null ? null : Set.copyOf(oncePerTurn);
    }

    /** A fresh budget for one turn. */
    public ToolCallBudget newTurn() {
        return new ToolCallBudget(maxCountedPerTurn, oncePerTurn);
    }
}
