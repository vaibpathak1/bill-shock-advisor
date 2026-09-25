package com.telco.billshock.tools;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Text that did not come from us (BSS descriptions, product and provider names). It is data,
 * never instructions (security.md §5 LLM01): control characters are removed and each value is
 * cut to 100 characters, and the system prompt tells the model how to treat this key.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Untrusted(String description, String name, String provider) {

    static final int MAX_LENGTH = 100;

    public static Untrusted description(String text) {
        return new Untrusted(sanitize(text), null, null);
    }

    public static Untrusted product(String name, String provider) {
        return new Untrusted(null, sanitize(name), sanitize(provider));
    }

    /** Control and format characters become spaces, whitespace is collapsed, at most 100 chars. */
    public static String sanitize(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").strip();
        return cleaned.length() <= MAX_LENGTH ? cleaned : cleaned.substring(0, MAX_LENGTH);
    }
}
