package com.telco.billshock.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** Points the chat model at the {@link FakeAnthropicApi}: no integration test ever calls Anthropic. */
@TestConfiguration(proxyBeanMethods = false)
public class FakeAnthropicApiConfiguration {

    @Bean
    DynamicPropertyRegistrar fakeAnthropicApi() {
        return registry -> registry.add("billshock.llm.chat.base-url", () -> FakeAnthropicApi.instance().baseUrl());
    }
}
