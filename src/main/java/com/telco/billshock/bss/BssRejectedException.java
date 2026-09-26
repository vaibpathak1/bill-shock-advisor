package com.telco.billshock.bss;

/**
 * The BSS answered and <b>definitely did not apply</b> the request: a business rejection such as
 * an unknown subscription (actions.md §6.3). Only this exception moves an action to
 * {@code FAILED}. A timeout, connection error or any other failure is an <b>unknown</b> outcome
 * ({@link BssUnavailableException}): the effect may have been applied, so the action stays
 * {@code EXECUTING} and is re-driven with the same idempotency key (A-112).
 */
public class BssRejectedException extends RuntimeException {

    private final String code;

    /** @param code the BSS rejection code, for example {@code UNKNOWN_SUBSCRIPTION} */
    public BssRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
