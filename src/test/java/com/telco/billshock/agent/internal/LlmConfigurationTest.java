package com.telco.billshock.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicChatOptions;

import com.telco.billshock.agent.LlmProperties;

/** The chat bean's options (llm-architecture.md §1, §14; ADR-008; Q-1). */
class LlmConfigurationTest {

    private static LlmProperties with(LlmProperties.Mode mode, String apiKey, Double temperature) {
        LlmProperties base = CostMeterTest.llm();
        LlmProperties.Chat c = base.chat();
        return new LlmProperties(mode, new LlmProperties.Chat(c.model(), c.maxTokens(), c.timeout(), apiKey, null,
                temperature, c.effort(), c.thinking(), c.maxRoundTrips(), c.maxInputTokensPerCall(), c.memoryWindow()),
                base.prompt(), base.pricing());
    }

    @Test
    void noSdkRetriesExplicitTimeoutNoTemperatureAndConversationCaching() {
        AnthropicChatOptions options = LlmConfiguration.chatOptions(with(LlmProperties.Mode.LLM, "k", null));

        assertThat(options.getMaxRetries()).isZero();
        assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getModel()).isEqualTo("claude-sonnet-5");
        assertThat(options.getMaxTokens()).isEqualTo(1024);
        assertThat(options.getCacheOptions().getStrategy()).isEqualTo(AnthropicCacheStrategy.CONVERSATION_HISTORY);
        assertThat(options.getCacheOptions().isCacheToolResults()).isTrue();
        assertThat(options.getThinking()).isNull();
        assertThat(options.getOutputConfig()).isNull();
    }

    @Test
    void aConfiguredTemperatureIsPassedOn() {
        assertThat(LlmConfiguration.chatOptions(with(LlmProperties.Mode.LLM, "k", 0.2)).getTemperature()).isEqualTo(0.2);
    }

    @Test
    void llmModeNeedsTheChatKeyButTheKillSwitchDoesNot() {
        assertThatThrownBy(() -> LlmConfiguration.chatOptions(with(LlmProperties.Mode.LLM, " ", null)))
            .hasMessageContaining("ANTHROPIC_API_KEY_CHAT");
        assertThat(LlmConfiguration.chatOptions(with(LlmProperties.Mode.TEMPLATE_ONLY, null, null)).getMaxRetries())
            .isZero();
    }
}
