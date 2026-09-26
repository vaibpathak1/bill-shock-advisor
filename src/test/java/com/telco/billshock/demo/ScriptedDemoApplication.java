package com.telco.billshock.demo;

import org.springframework.boot.SpringApplication;

import com.telco.billshock.BillShockAdvisorApplication;
import com.telco.billshock.support.FakeAnthropicApi;
import com.telco.billshock.support.ScenarioScripts;

/**
 * The scripted demo (actions.md §8.2; owner answer 3, option c): the real application, profile
 * {@code dev}, with the chat model pointed at the local {@link FakeAnthropicApi}, which answers from
 * the same {@link ScenarioScripts} as the scenario E2E tests. Everything except the model's words
 * and tool choices runs for real. It lives in test sources, so nothing scripted ships in the jar.
 * The page and the README say "Scripted model — not a live LLM".
 *
 * <p>Run: {@code ./mvnw spring-boot:test-run
 * -Dspring-boot.run.main-class=com.telco.billshock.demo.ScriptedDemoApplication} (README).
 * The API key and base URL are set as system properties here, which take precedence over the
 * environment: no request can reach Anthropic, and no real key is sent anywhere.
 */
public final class ScriptedDemoApplication {

    private ScriptedDemoApplication() {
    }

    public static void main(String[] args) {
        FakeAnthropicApi api = FakeAnthropicApi.instance();
        api.select(ScenarioScripts::select);
        System.setProperty("billshock.llm.mode", "LLM");
        System.setProperty("billshock.llm.chat.api-key", "scripted-demo-not-a-key");
        System.setProperty("billshock.llm.chat.base-url", api.baseUrl());
        System.setProperty("billshock.meta.scripted-model", "true");
        SpringApplication.from(BillShockAdvisorApplication::main).withAdditionalProfiles("dev").run(args);
    }
}
