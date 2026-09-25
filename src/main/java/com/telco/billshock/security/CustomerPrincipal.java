package com.telco.billshock.security;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.telco.billshock.domain.AccountId;

/**
 * An authenticated customer bound to exactly one account (security.md §2: the
 * {@code account_id} claim). In the MVP slice it comes from the in-memory demo users; in
 * Phase 8 from the JWT.
 */
public final class CustomerPrincipal implements UserDetails {

    private final String username;
    private final String passwordHash;
    private final AccountId accountId;

    public CustomerPrincipal(String username, String passwordHash, AccountId accountId) {
        this.username = Objects.requireNonNull(username, "username");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
    }

    public AccountId accountId() {
        return accountId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String toString() {
        return "CustomerPrincipal[" + username + "]";
    }
}
