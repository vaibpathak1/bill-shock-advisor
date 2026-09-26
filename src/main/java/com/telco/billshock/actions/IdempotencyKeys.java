package com.telco.billshock.actions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** {@code Idempotency-Key} rules (actions.md §4, §6.6 rule 1). */
public final class IdempotencyKeys {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    private IdempotencyKeys() {
    }

    public static boolean valid(String key) {
        return key != null && VALID.matcher(key).matches();
    }

    /**
     * SHA-256 over the method, the path and the canonical body. The path is part of it, so the same
     * key on another action or on {@code /reject} is a different request (422).
     */
    public static String requestHash(String method, String path, String canonicalBody) {
        String text = method + " " + path + "\n" + (canonicalBody == null ? "" : canonicalBody);
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
