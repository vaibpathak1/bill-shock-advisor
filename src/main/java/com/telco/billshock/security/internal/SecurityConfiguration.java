package com.telco.billshock.security.internal;

import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        return http
            .authorizeHttpRequests(auth -> auth
                // SSE completes on an ASYNC dispatch; the original request was already authorised.
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/**").hasRole("CUSTOMER")
                .anyRequest().denyAll())
            .httpBasic(Customizer.withDefaults())
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
