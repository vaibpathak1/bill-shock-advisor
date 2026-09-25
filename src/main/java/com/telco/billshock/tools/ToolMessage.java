package com.telco.billshock.tools;

/**
 * A tool result that carries no data, only a status the model must pass on honestly.
 *
 * @param status {@code NOT_FOUND}, {@code INVALID_ARGUMENT} or {@code UNAVAILABLE}
 */
public record ToolMessage(String status, String message) implements ToolResult {

    static ToolMessage noBill(String period) {
        return new ToolMessage("NOT_FOUND",
                period == null ? "There is no bill on this account yet." : "There is no bill for " + period + ".");
    }

    static ToolMessage invalid(String message) {
        return new ToolMessage("INVALID_ARGUMENT", message);
    }
}
