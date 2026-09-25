package com.telco.billshock.tools;

/**
 * Marker for everything a tool returns. A condition the model should explain (no bill, bad
 * period, BSS down) is a normal result such as {@link ToolMessage}, not an exception
 * (agent.md §6).
 */
public interface ToolResult {
}
