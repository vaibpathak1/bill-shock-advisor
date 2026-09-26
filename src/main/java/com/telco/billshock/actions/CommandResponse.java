package com.telco.billshock.actions;

/**
 * The HTTP response of a confirm or reject, exactly as stored in {@code idempotency_record}, so a
 * replay with the same key returns the same status and body (actions.md §6.6).
 *
 * @param problem the body is an RFC 9457 problem ({@code application/problem+json})
 */
public record CommandResponse(int status, String body, boolean problem) {
}
