package com.telco.billshock.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A Spring Boot test against the seeded Testcontainers database (needs Docker; run by Failsafe),
 * with the web server on a random port and the chat model pointed at the {@link FakeAnthropicApi}.
 * All ITs share one context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({ PostgresTestcontainers.class, FakeAnthropicApiConfiguration.class })
public @interface IntegrationTest {
}
