package com.telco.billshock.analysis;

import java.util.Optional;

/**
 * The groups in which bill changes are explained (deterministic-core.md §2.2). RENTAL and
 * PRORATION share {@link #PLAN}, so a mid-cycle plan change shows as one net plan cause.
 */
public enum CauseGroup {

    PLAN, DATA, VOICE, SMS, ROAMING, VAS, OTHER;

    /** The group of a line-item category; empty for TAX lines. */
    public static Optional<CauseGroup> of(String category) {
        return switch (category) {
            case "RENTAL", "PRORATION" -> Optional.of(PLAN);
            case "DATA" -> Optional.of(DATA);
            case "VOICE" -> Optional.of(VOICE);
            case "SMS" -> Optional.of(SMS);
            case "ROAMING" -> Optional.of(ROAMING);
            case "VAS" -> Optional.of(VAS);
            case "TAX" -> Optional.empty();
            default -> Optional.of(OTHER);
        };
    }
}
