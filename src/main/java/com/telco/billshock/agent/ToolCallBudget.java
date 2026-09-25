package com.telco.billshock.agent;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The per-turn tool-call budget (SPEC §4.5; owner decisions 2026-09-25; llm-architecture.md
 * §9). A pure policy with no Spring AI dependency: the {@code TurnToolBudget}
 * {@code ToolCallingManager} decorator (Phase 5a) asks it before each tool call. One
 * instance per turn; not thread-safe.
 *
 * <ul>
 * <li>Every tool except the once-per-turn tools counts; the call after the limit stops the
 * turn ({@link Admission#LIMIT_REACHED}: escalate and answer from the template).</li>
 * <li>Once-per-turn tools ({@code escalateToHuman}, {@code recordDiagnosis}) do not count and
 * are allowed once each, even after the limit, so the agent can always escalate. A repeat
 * is not executed and the turn continues ({@link Admission#ALREADY_CALLED_THIS_TURN}).</li>
 * </ul>
 */
public final class ToolCallBudget {

    public enum Admission {

        /** Execute the call. */
        ALLOWED,

        /** A once-per-turn tool was already called: do not execute; the result says so; continue. */
        ALREADY_CALLED_THIS_TURN,

        /** The counted limit is exhausted: stop the tool loop and escalate. */
        LIMIT_REACHED
    }

    private final int maxCounted;
    private final Set<String> oncePerTurn;
    private final Set<String> onceUsed = new HashSet<>();
    private int counted;

    public ToolCallBudget(int maxCounted, Set<String> oncePerTurn) {
        if (maxCounted < 1) {
            throw new IllegalArgumentException("maxCounted must be at least 1: " + maxCounted);
        }
        this.maxCounted = maxCounted;
        this.oncePerTurn = Set.copyOf(oncePerTurn);
    }

    /** Decides on one call and records it if allowed. */
    public Admission admit(String toolName) {
        Objects.requireNonNull(toolName, "toolName");
        if (oncePerTurn.contains(toolName)) {
            return onceUsed.add(toolName) ? Admission.ALLOWED : Admission.ALREADY_CALLED_THIS_TURN;
        }
        if (counted >= maxCounted) {
            return Admission.LIMIT_REACHED;
        }
        counted++;
        return Admission.ALLOWED;
    }

    /** Calls counted so far in this turn. */
    public int countedCalls() {
        return counted;
    }
}
