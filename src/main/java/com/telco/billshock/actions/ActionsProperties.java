package com.telco.billshock.actions;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * ProposedAction workflow settings (actions.md §5.1, §6.6).
 *
 * @param pendingTtl how long a {@code PENDING_CONFIRMATION} proposal can be confirmed (A-111)
 */
@Validated
@ConfigurationProperties("billshock.actions")
public record ActionsProperties(@NotNull Duration pendingTtl) {
}
