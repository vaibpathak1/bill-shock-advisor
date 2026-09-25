package com.telco.billshock.bss;

/**
 * A BSS gateway could not answer (timeout, 5xx, circuit open). Callers say honestly that the
 * detail is unavailable (SPEC §4.8; llm-architecture.md §6).
 */
public class BssUnavailableException extends RuntimeException {

    public BssUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
