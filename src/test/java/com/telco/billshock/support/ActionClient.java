package com.telco.billshock.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Plain HTTP calls to the REST API as a demo customer (or anonymously), for the action ITs. */
public final class ActionClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final int port;

    public ActionClient(int port) {
        this.port = port;
    }

    public record Response(int status, String body, HttpResponse<String> raw) {

        public JsonNode json() {
            return JSON.readTree(body);
        }

        public String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    public Response confirm(String user, long actionId, String idempotencyKey) {
        return post(user, "/api/v1/actions/" + actionId + "/confirm", idempotencyKey, null);
    }

    public Response reject(String user, long actionId, String idempotencyKey, String reason) {
        return post(user, "/api/v1/actions/" + actionId + "/reject", idempotencyKey,
                reason == null ? null : JSON.writeValueAsString(java.util.Map.of("reason", reason)));
    }

    public Response post(String user, String path, String idempotencyKey, String jsonBody) {
        HttpRequest.Builder b = request(user, path)
            .POST(jsonBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(jsonBody));
        if (jsonBody != null) {
            b.header("Content-Type", "application/json");
        }
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        return send(b.build());
    }

    public Response get(String user, String path) {
        return send(request(user, path).GET().build());
    }

    /** As the chat page sends it: no Basic-auth pop-up on a 401. */
    public Response getAsPage(String path) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("X-Requested-With", "XMLHttpRequest")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build());
    }

    private HttpRequest.Builder request(String user, String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(30));
        if (user != null) {
            b.header("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + SseClient.PASSWORD).getBytes(StandardCharsets.UTF_8)));
        }
        return b;
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body(), r);
        }
        catch (IOException e) {
            throw new IllegalStateException(e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
