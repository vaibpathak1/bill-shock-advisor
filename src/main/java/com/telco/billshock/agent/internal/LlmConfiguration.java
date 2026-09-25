package com.telco.billshock.agent.internal;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.anthropic.models.messages.OutputConfig;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.telco.billshock.agent.LlmProperties;
import com.telco.billshock.agent.LlmProperties.Mode;
import com.telco.billshock.agent.ToolCallBudgetProperties;
import com.telco.billshock.tools.BillingTools;
import com.telco.billshock.tools.CatalogTools;
import com.telco.billshock.tools.UsageTools;

/**
 * The chat model, ChatClient and tool wiring, built explicitly: no Spring AI starter, so
 * nothing is auto-configured (llm-architecture.md §1; ADR-008; 5a answer Q-A). The Boot
 * {@link ObservationRegistry} is passed to the model, the client and the tool manager, so
 * {@code gen_ai.client.operation}, {@code spring.ai.chat.client} and {@code spring.ai.tool}
 * are recorded (agent.md F-19).
 */
@Configuration(proxyBeanMethods = false)
class LlmConfiguration {

    /** Used only when the kill switch is on, so that no real key is needed or read. */
    private static final String TEMPLATE_ONLY_KEY = "template-only-mode-no-key";

    @Bean
    DefaultToolCallingManager toolCallingManager(ObjectProvider<ObservationRegistry> observationRegistry,
            ToolCallBudgetProperties budget) {
        DefaultToolCallingManager.Builder builder = DefaultToolCallingManager.builder()
            .observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
            // An unexpected tool error ends the turn with the template; its message never reaches the model (F-16).
            .toolExecutionExceptionProcessor(DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(true).build())
            // Backstop behind TurnToolBudget (llm-architecture.md §9): 8 counted + 1 + 1.
            .maxTotalToolCalls(budget.maxCountedPerTurn() + budget.oncePerTurn().size())
            .onLimitExceeded(ToolCallLimitBehavior.THROW);
        budget.oncePerTurn().forEach(name -> builder.maxCallsPerTool(name, 1));
        return builder.build();
    }

    @Bean
    AnthropicChatModel chatModel(LlmProperties properties, ObjectProvider<ObservationRegistry> observationRegistry) {
        // No toolCallingManager: the model no longer executes tools (F-10); the orchestrator does.
        return AnthropicChatModel.builder()
            .options(chatOptions(properties))
            .observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
            .build();
    }

    @Bean
    ChatClient chatClient(AnthropicChatModel chatModel, ObjectProvider<ObservationRegistry> observationRegistry) {
        return ChatClient.builder(chatModel, observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP), null,
                null)
            .build();
    }

    /**
     * The read-only tools plus {@code recordDiagnosis}, sorted by name, so the tool definitions
     * (the start of the cached prefix) are identical on every request (llm-architecture.md §5).
     */
    @Bean
    ChatTools chatTools(BillingTools billing, UsageTools usage, CatalogTools catalog, DiagnosisTool diagnosis) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
            .toolObjects(billing, usage, catalog, diagnosis)
            .build()
            .getToolCallbacks();
        return new ChatTools(Arrays.stream(callbacks)
            .sorted(Comparator.comparing(c -> c.getToolDefinition().name()))
            .toList());
    }

    static AnthropicChatOptions chatOptions(LlmProperties properties) {
        LlmProperties.Chat chat = properties.chat();
        String apiKey = chat.apiKey();
        if (properties.mode() == Mode.LLM && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalStateException(
                    "billshock.llm.mode=LLM needs ANTHROPIC_API_KEY_CHAT (or set BILLSHOCK_LLM_MODE=TEMPLATE_ONLY)");
        }
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder()
            .model(chat.model())
            .maxTokens(chat.maxTokens())
            .timeout(chat.timeout())
            // Resilience4j (5b) is the only retry layer; the SDK default would retry twice (ADR-008).
            .maxRetries(0)
            .apiKey(properties.mode() == Mode.LLM ? apiKey : TEMPLATE_ONLY_KEY)
            .cacheOptions(AnthropicCacheOptions.builder()
                .strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY)
                .cacheToolResults(true)
                .build());
        if (chat.baseUrl() != null && !chat.baseUrl().isBlank()) {
            builder.baseUrl(chat.baseUrl());
        }
        if (chat.temperature() != null) {
            builder.temperature(chat.temperature());
        }
        if (chat.effort() != null && !chat.effort().isBlank()) {
            builder.effort(OutputConfig.Effort.of(chat.effort().strip().toLowerCase(Locale.ROOT)));
        }
        if (chat.thinking() != null && !chat.thinking().isBlank()) {
            switch (chat.thinking().strip().toLowerCase(Locale.ROOT)) {
                case "adaptive" -> builder.thinkingAdaptive();
                case "disabled" -> builder.thinkingDisabled();
                default -> throw new IllegalStateException("billshock.llm.chat.thinking must be adaptive or disabled");
            }
        }
        return builder.build();
    }

    /** The tool callbacks offered to the chat model, in a fixed order. */
    record ChatTools(List<ToolCallback> callbacks) {

        ChatTools {
            callbacks = List.copyOf(callbacks);
        }
    }
}
