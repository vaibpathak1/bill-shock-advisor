package com.telco.billshock.api;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.MediaType;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.telco.billshock.agent.ChatOrchestrator;
import com.telco.billshock.agent.ChatTurn;

/**
 * {@code POST /api/v1/chat} as server-sent events (SPEC §4.7; agent.md §4.1). The turn is
 * validated on the request thread (unknown conversation → 404), then runs on a virtual thread
 * that carries the caller's SecurityContext, so every tool sees the caller's identity (agent.md §7).
 */
@RestController
@RequestMapping("/api/v1/chat")
class ChatController {

    static final Duration STREAM_TIMEOUT = Duration.ofMinutes(2);
    static final Duration HEARTBEAT = Duration.ofSeconds(15);

    private final ChatOrchestrator orchestrator;
    private final ExecutorService turns = new DelegatingSecurityContextExecutorService(
            Executors.newVirtualThreadPerTaskExecutor());
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("sse-heartbeat").factory());

    ChatController(ChatOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * @param conversationId omit to start a new conversation
     * @param message the customer's text; personal identifiers are scrubbed before storage (security.md §6.2)
     */
    record ChatRequest(UUID conversationId, @NotBlank @Size(max = 2000) String message) {
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter chat(@Valid @RequestBody ChatRequest request) {
        ChatTurn turn = orchestrator.start(request.conversationId(), request.message());
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        AtomicBoolean open = new AtomicBoolean(true);
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(
                () -> send(emitter, open, SseEmitter.event().comment("keep-alive")), HEARTBEAT.toMillis(),
                HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(e -> open.set(false));
        turns.execute(() -> {
            try {
                orchestrator.run(turn, event -> send(emitter, open,
                        SseEmitter.event().name(event.name()).data(event, MediaType.APPLICATION_JSON)));
            }
            finally {
                heartbeat.cancel(false);
                if (open.get()) {
                    emitter.complete();
                }
            }
        });
        return emitter;
    }

    /** A disconnected client stops receiving events; the turn itself still completes and is stored. */
    private static void send(SseEmitter emitter, AtomicBoolean open, SseEmitter.SseEventBuilder event) {
        if (!open.get()) {
            return;
        }
        synchronized (emitter) {
            try {
                emitter.send(event);
            }
            catch (IOException | IllegalStateException e) {
                open.set(false);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        heartbeats.shutdownNow();
        turns.shutdown();
    }
}
