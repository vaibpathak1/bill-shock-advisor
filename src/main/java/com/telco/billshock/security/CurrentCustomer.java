package com.telco.billshock.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.telco.billshock.domain.AccountId;

/**
 * The only way to learn whose data a request may touch (SPEC §4.3 rule 2; security.md §2).
 * The account comes from the authenticated principal, never from a request or tool
 * parameter.
 */
public final class CurrentCustomer {

    private CurrentCustomer() {
    }

    /** The current customer's account; throws if the caller is not an authenticated customer. */
    public static AccountId require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof CustomerPrincipal customer)) {
            throw new AccessDeniedException("No authenticated customer");
        }
        return customer.accountId();
    }
}
