package com.telco.billshock.bss;

/**
 * A BSS gateway could not answer (timeout, 5xx, circuit open). Callers say honestly that the
 * detail is unavailable (SPEC §4.8; llm-architecture.md §6). For a write, the outcome is
 * <b>unknown</b>: the request may have been applied (actions.md §6.3; compare
 * {@link BssRejectedException}).
 */
public class BssUnavailableException extends RuntimeException {

    public BssUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public BssUnavailableException(String message) {
        super(message);
    }
}
