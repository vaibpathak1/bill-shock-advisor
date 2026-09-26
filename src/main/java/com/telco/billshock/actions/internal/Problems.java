package com.telco.billshock.actions.internal;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

import com.telco.billshock.actions.CommandResponse;

/**
 * RFC 9457 bodies for confirm/reject outcomes. They are built here, not in the controller advice,
 * because they are stored with the idempotency key and replayed as they are (actions.md §6.6).
 * Same shape as the API's other problems: {@code type}, {@code title}, {@code status},
 * {@code detail}, {@code code}.
 */
final class Problems {

    private Problems() {
    }

    static CommandResponse response(JsonMapper json, int status, String code, String title, String detail,
            Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:billshock:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-'));
        body.put("title", title);
        body.put("status", status);
        body.put("detail", detail);
        body.put("code", code);
        body.putAll(extra);
        return new CommandResponse(status, json.writeValueAsString(body), true);
    }
}
