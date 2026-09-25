package com.telco.billshock.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Posts to {@code /api/v1/chat} over real HTTP and collects the server-sent events with arrival times. */
public final class SseClient {

    public static final String PASSWORD = "test-password-not-a-secret";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final int port;

    public SseClient(int port) {
        this.port = port;
    }

    public record Event(String name, JsonNode data, long millisAfterRequest) {
    }

    public record Response(int status, String body, List<Event> events) {

        public List<String> names() {
            return events.stream().map(Event::name).toList();
        }

        public List<Event> named(String name) {
            return events.stream().filter(e -> e.name().equals(name)).toList();
        }

        public Event first(String name) {
            return events.stream()
                .filter(e -> e.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No '" + name + "' event in " + names()));
        }

        /** The concatenated text of all {@code token} events. */
        public String tokens() {
            StringBuilder b = new StringBuilder();
            named("token").forEach(e -> b.append(e.data().get("text").asString()));
            return b.toString();
        }
    }

    public Response chat(String user, String conversationId, String message) {
        String body = JSON.writeValueAsString(conversationId == null
                ? java.util.Map.of("message", message)
                : java.util.Map.of("conversationId", conversationId, "message", message));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/chat"))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Authorization", basic(user))
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        long start = System.nanoTime();
        try {
            HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                String text = String.join("\n", response.body().toList());
                return new Response(response.statusCode(), text, List.of());
            }
            List<Event> events = new ArrayList<>();
            String name = null;
            StringBuilder data = new StringBuilder();
            Iterator<String> lines = response.body().iterator();
            while (lines.hasNext()) {
                String line = lines.next();
                if (line.startsWith("event:")) {
                    name = line.substring(6).strip();
                }
                else if (line.startsWith("data:")) {
                    data.append(line.substring(5));
                }
                else if (line.isEmpty() && name != null) {
                    events.add(new Event(name, JSON.readTree(data.toString()), (System.nanoTime() - start) / 1_000_000));
                    name = null;
                    data.setLength(0);
                }
            }
            return new Response(200, "", events);
        }
        catch (IOException e) {
            throw new IllegalStateException(e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String basic(String user) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((user + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }
}
