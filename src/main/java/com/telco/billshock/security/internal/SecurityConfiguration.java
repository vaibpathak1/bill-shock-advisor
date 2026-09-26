package com.telco.billshock.security.internal;

import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import com.telco.billshock.domain.AccountId;
import com.telco.billshock.security.CustomerPrincipal;
import com.telco.billshock.security.DemoUserProperties;

/**
 * MVP-slice security (security.md §2; plan-and-budget §2a): HTTP Basic against in-memory
 * demo customers, stateless, no cookies (so CSRF protection is not needed for
 * {@code /api/**}). Keycloak and JWT arrive in Phase 8.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    /** No inline or third-party script, style or framing (actions.md §8.1). */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self';"
            + " img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        return http
            .authorizeHttpRequests(auth -> auth
                // SSE completes on an ASYNC dispatch; the original request was already authorised.
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health").permitAll()
                // The demo chat page and its banner flag (actions.md §8.1); the page signs in itself.
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/app.js", "/app.css").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/meta").permitAll()
                .requestMatchers("/api/**").hasRole("CUSTOMER")
                .anyRequest().denyAll())
            // With X-Requested-With: XMLHttpRequest (the chat page), a 401 carries no WWW-Authenticate
            // header, so the browser shows no Basic-auth pop-up.
            .httpBasic(Customizer.withDefaults())
            .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY)))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService demoUsers(DemoUserProperties properties, PasswordEncoder encoder) {
        Map<String, CustomerPrincipal> users = Map.of();
        if (properties.enabled()) {
            if (properties.password() == null || properties.password().isBlank()) {
                throw new IllegalStateException(
                        "billshock.security.demo-users.enabled=true needs DEMO_USER_PASSWORD (see .env.example)");
            }
            String hash = encoder.encode(properties.password());
            users = properties.users().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        e -> new CustomerPrincipal(e.getKey(), hash, AccountId.of(e.getValue()))));
        }
        Map<String, CustomerPrincipal> byName = users;
        return username -> {
            CustomerPrincipal user = byName.get(username);
            if (user == null) {
                throw new UsernameNotFoundException("Unknown user");
            }
            return user;
        };
    }
}
