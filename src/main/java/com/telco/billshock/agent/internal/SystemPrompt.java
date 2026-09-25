package com.telco.billshock.agent.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.stringtemplate.v4.ST;

import com.telco.billshock.agent.LlmProperties;

/**
 * The rendered system prompt, {@code prompts/system.<version>.st} (llm-architecture.md §10).
 * Rendered once at startup with values that are constant per deployment (locale, canary), so
 * the text is identical for every customer and the cached prefix is shared (§5). The version
 * and a SHA-256 of the text identify it in logs, the {@code conversation} row and each call.
 */
@Component
class SystemPrompt {

    private final String version;
    private final String text;
    private final String sha256;

    SystemPrompt(LlmProperties properties) {
        this.version = properties.prompt().version();
        String file = "prompts/system." + version + ".st";
        try (InputStream in = new ClassPathResource(file).getInputStream()) {
            ST st = new ST(new String(in.readAllBytes(), StandardCharsets.UTF_8), '$', '$');
            st.add("locale", properties.prompt().locale());
            st.add("canary", properties.prompt().canary());
            this.text = st.render().strip();
        }
        catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
        this.sha256 = TurnToolBudget.sha256(text);
    }

    String version() {
        return version;
    }

    String text() {
        return text;
    }

    /** {@code v1+<first 12 hex of the SHA-256>}: logged with every response (SPEC §2.6). */
    String versionTag() {
        return version + "+" + sha256.substring(0, 12);
    }
}
