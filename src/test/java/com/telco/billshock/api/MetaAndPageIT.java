package com.telco.billshock.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.web.server.LocalServerPort;

import com.telco.billshock.support.ActionClient;
import com.telco.billshock.support.ActionClient.Response;
import com.telco.billshock.support.IntegrationTest;

/** The demo page, its banner flag and their security headers (actions.md §8.1, §12 answer 6). */
@IntegrationTest
class MetaAndPageIT {

    @LocalServerPort
    int port;

    ActionClient http;

    @BeforeEach
    void setUp() {
        http = new ActionClient(port);
    }

    /** Owner answer 6: only the banner flag and the version; no thresholds, model names or other config. */
    @Test
    void metaReturnsExactlyTheBannerFlagAndTheVersion() {
        Response r = http.getAsPage("/api/v1/meta");

        assertThat(r.status()).isEqualTo(200);
        List<String> keys = new ArrayList<>();
        r.json().propertyNames().forEach(keys::add);
        assertThat(keys).containsExactly("demoMode", "banner", "version");
        assertThat(r.json().get("demoMode").asBoolean()).isFalse();
        assertThat(r.json().get("banner").isNull()).isTrue();
        assertThat(r.json().get("version").asString()).matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?");
        assertThat(r.body()).doesNotContain("claude", "sonnet", "500", "2000", "15", "autonomy");
    }

    @Test
    void thePageIsPublicWithAStrictContentSecurityPolicy() {
        for (String path : List.of("/", "/index.html", "/app.js", "/app.css")) {
            Response r = http.getAsPage(path);
            assertThat(r.status()).as(path).isEqualTo(200);
            assertThat(r.header("Content-Security-Policy")).as(path)
                .contains("script-src 'self'")
                .contains("frame-ancestors 'none'")
                .doesNotContain("unsafe-inline");
        }
        assertThat(http.getAsPage("/").body()).contains("<script src=\"app.js\" defer></script>")
            .doesNotContain("<script>");
    }

    @Test
    void theApiStillNeedsASignInWithoutABasicAuthPopUpForThePage() {
        Response r = http.getAsPage("/api/v1/actions");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.header("WWW-Authenticate")).isNull();
        assertThat(http.getAsPage("/actuator/env").status()).isIn(401, 403);
    }
}
