package com.telco.billshock.actions.guardrail;

import java.time.Instant;

import com.telco.billshock.domain.AccountId;

/**
 * Goodwill credits issued through this system, for the "no credit in the last 6 months" rule
 * (actions.md §5.2). Credits whose outcome is still unknown ({@code EXECUTING}) count too: the
 * credit may already be applied (A-112).
 */
public interface CreditHistory {

    /** For unit tests and contexts without the workflow: no credits issued here. */
    CreditHistory NONE = (account, from) -> false;

    boolean goodwillCreditSince(AccountId account, Instant from);
}
