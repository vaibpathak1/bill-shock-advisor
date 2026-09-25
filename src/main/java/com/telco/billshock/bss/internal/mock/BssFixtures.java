package com.telco.billshock.bss.internal.mock;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Loads the JSON fixtures that the in-process mock gateways serve
 * ({@code classpath:bss-fixtures/}, A-80). Content: seed-scenarios.md §6.
 */
final class BssFixtures {

    private BssFixtures() {
    }

    static <T> T load(JsonMapper jsonMapper, String name, Class<T> type) {
        ClassPathResource resource = new ClassPathResource("bss-fixtures/" + name);
        try (InputStream in = resource.getInputStream()) {
            return jsonMapper.readValue(in, type);
        }
        catch (IOException ex) {
            throw new UncheckedIOException("Cannot read BSS fixture " + name, ex);
        }
    }
}
