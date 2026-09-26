package com.telco.billshock.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/meta}: the chat page's banner flag and the app version, public so the page can
 * show the banner before sign-in. It returns <b>only</b> these three fields: no thresholds, model
 * names or other configuration (owner answer 6; a test asserts the exact key set).
 */
@RestController
class MetaController {

    static final String SCRIPTED_BANNER = "Scripted model — not a live LLM";

    private final MetaProperties properties;

    MetaController(MetaProperties properties) {
        this.properties = properties;
    }

    /** @param banner shown at the top of the page; {@code null} unless {@code demoMode} */
    record Meta(boolean demoMode, String banner, String version) {
    }

    @GetMapping("/api/v1/meta")
    Meta meta() {
        return new Meta(properties.scriptedModel(), properties.scriptedModel() ? SCRIPTED_BANNER : null,
                properties.version());
    }
}
