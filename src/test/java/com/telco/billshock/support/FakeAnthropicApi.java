package com.telco.billshock.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A local stand-in for the Anthropic Messages API ({@code POST /v1/messages}, streaming). The
 * real {@code AnthropicChatModel} bean talks to it through {@code billshock.llm.chat.base-url},
 * so the ITs exercise the real SDK, request building (cache breakpoints, no temperature) and
 * observations. Tests queue one {@link Script} per expected round trip and read the recorded
 * request bodies. One server per JVM; tests run sequentially (Failsafe).
 */
public final class FakeAnthropicApi {

    private static final FakeAnthropicApi INSTANCE = new FakeAnthropicApi();

    private final HttpServer server;
    private final ConcurrentLinkedQueue<Script> scripts = new ConcurrentLinkedQueue<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile Function<String, Script> selector;

    private FakeAnthropicApi() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }
        catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/v1/messages", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public static FakeAnthropicApi instance() {
        return INSTANCE;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Clears scripts, the selector and recorded requests (call in {@code @BeforeEach}). */
    public void reset() {
        scripts.clear();
        requests.clear();
        selector = null;
    }

    /**
     * Answers from the request body when no script is queued: the scripted demo and the scenario E2E
     * tests pick the script by scenario and round ({@link ScenarioScripts#select}).
     */
    public void select(Function<String, Script> selector) {
        this.selector = selector;
    }

    public void enqueue(Script... more) {
        scripts.addAll(List.of(more));
    }

    public List<String> requests() {
        return List.copyOf(requests);
    }

    public int remainingScripts() {
        return scripts.size();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        requests.add(body);
        Script script = scripts.poll();
        Function<String, Script> select = selector;
        if (script == null && select != null) {
            script = select.apply(body);
        }
        if (script == null) {
            script = Script.error(500);
        }
        if (script.delay != null) {
            try {
                Thread.sleep(script.delay.toMillis());
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (script.status != 200) {
            byte[] error = ("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"scripted "
                    + script.status + "\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(script.status, error.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(error);
            }
            return;
        }
        exchange.getResponseHeaders().add("content-type", "text/event-stream");
        exchange.getResponseHeaders().add("request-id", "req_fake_" + requests.size());
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String event : script.events()) {
                out.write(event.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
        catch (IOException e) {
            // The client cancelled the stream (grounding stop); nothing to do.
        }
    }

    /** One scripted response: a streamed message, an HTTP error, or a slow reply. */
    public static final class Script {

        private final int status;
        private final Duration delay;
        private final List<String> texts;
        private final List<ToolUse> toolUses;
        private int inputTokens = 1200;
        private int cacheWriteTokens = 0;
        private int cacheReadTokens = 0;
        private int outputTokens = 60;

        private Script(int status, Duration delay, List<String> texts, List<ToolUse> toolUses) {
            this.status = status;
            this.delay = delay;
            this.texts = texts;
            this.toolUses = toolUses;
        }

        /** A final answer, streamed in the given chunks. */
        public static Script text(String... chunks) {
            return new Script(200, null, List.of(chunks), List.of());
        }

        /** Optional preamble text, then tool calls ({@code stop_reason: tool_use}). */
        public static Script tools(String preamble, ToolUse... uses) {
            return new Script(200, null, preamble == null ? List.of() : List.of(preamble), List.of(uses));
        }

        public static Script error(int status) {
            return new Script(status, null, List.of(), List.of());
        }

        /** A streamed answer that starts only after {@code delay} (longer than the client timeout). */
        public static Script slow(Duration delay) {
            return new Script(200, delay, List.of("Too late."), List.of());
        }

        public Script usage(int input, int cacheWrite, int cacheRead, int output) {
            this.inputTokens = input;
            this.cacheWriteTokens = cacheWrite;
            this.cacheReadTokens = cacheRead;
            this.outputTokens = output;
            return this;
        }

        List<String> events() {
            List<String> events = new ArrayList<>();
            events.add(sse("message_start", """
                    {"type":"message_start","message":{"id":"msg_fake","type":"message","role":"assistant",\
                    "model":"claude-sonnet-5","content":[],"stop_reason":null,"stop_sequence":null,\
                    "usage":{"input_tokens":%d,"output_tokens":1,"cache_creation_input_tokens":%d,\
                    "cache_read_input_tokens":%d}}}""".formatted(inputTokens, cacheWriteTokens, cacheReadTokens)));
            int index = 0;
            if (!texts.isEmpty()) {
                events.add(sse("content_block_start", """
                        {"type":"content_block_start","index":%d,"content_block":{"type":"text","text":""}}"""
                    .formatted(index)));
                for (String t : texts) {
                    events.add(sse("content_block_delta", """
                            {"type":"content_block_delta","index":%d,"delta":{"type":"text_delta","text":%s}}"""
                        .formatted(index, json(t))));
                }
                events.add(sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":%d}".formatted(index)));
                index++;
            }
            for (ToolUse u : toolUses) {
                events.add(sse("content_block_start", """
                        {"type":"content_block_start","index":%d,"content_block":{"type":"tool_use","id":"%s",\
                        "name":"%s","input":{}}}""".formatted(index, u.id(), u.name())));
                events.add(sse("content_block_delta", """
                        {"type":"content_block_delta","index":%d,"delta":{"type":"input_json_delta","partial_json":%s}}"""
                    .formatted(index, json(u.inputJson()))));
                events.add(sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":%d}".formatted(index)));
                index++;
            }
            events.add(sse("message_delta", """
                    {"type":"message_delta","delta":{"stop_reason":"%s","stop_sequence":null},\
                    "usage":{"input_tokens":%d,"output_tokens":%d,"cache_creation_input_tokens":%d,\
                    "cache_read_input_tokens":%d}}""".formatted(toolUses.isEmpty() ? "end_turn" : "tool_use",
                    inputTokens, outputTokens, cacheWriteTokens, cacheReadTokens)));
            events.add(sse("message_stop", "{\"type\":\"message_stop\"}"));
            return events;
        }

        private static String sse(String event, String data) {
            return "event: " + event + "\ndata: " + data + "\n\n";
        }

        private static String json(String s) {
            StringBuilder b = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    default -> b.append(c);
                }
            }
            return b.append('"').toString();
        }
    }

    public record ToolUse(String id, String name, String inputJson) {

        public static ToolUse of(String id, String name) {
            return new ToolUse(id, name, "{}");
        }
    }
}
