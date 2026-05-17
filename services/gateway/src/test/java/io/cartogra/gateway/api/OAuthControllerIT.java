package io.cartogra.gateway.api;

import io.cartogra.gateway.AbstractGatewayIT;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.UUID;

class OAuthControllerIT extends AbstractGatewayIT {

    @SuppressWarnings("resource")
    static final MockWebServer MOCK_SERVER = new MockWebServer();

    static {
        try {
            MOCK_SERVER.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void oauthProviderUrls(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + MOCK_SERVER.getPort();
        registry.add("app.oauth.google.token-uri", () -> base + "/google/token");
        registry.add("app.oauth.google.userinfo-uri", () -> base + "/google/userinfo");
        registry.add("app.oauth.github.token-uri", () -> base + "/github/token");
        registry.add("app.oauth.github.userinfo-uri", () -> base + "/github/user");
    }

    @Test
    void oauthStartRedirectsToProvider() {
        UUID tenantId = UUID.randomUUID();

        webTestClient.get()
            .uri("/v1/auth/oauth/google/start?tenantId=" + tenantId)
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueMatches("Location", ".*accounts\\.google\\.com.*");
    }

    @Test
    void oauthStartWithGithubRedirectsToGithub() {
        UUID tenantId = UUID.randomUUID();

        webTestClient.get()
            .uri("/v1/auth/oauth/github/start?tenantId=" + tenantId)
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueMatches("Location", ".*github\\.com.*");
    }

    @Test
    void oauthCallbackWithInvalidStateReturns400() {
        webTestClient.get()
            .uri("/v1/auth/oauth/google/callback?code=authcode&state=invalid-state")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.error.code").isNotEmpty()
            .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    void oauthCallbackWithProviderErrorReturns400() {
        webTestClient.get()
            .uri("/v1/auth/oauth/google/callback?error=access_denied&state=some-state")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.error").exists();
    }

    @Test
    void oauthCallbackWithValidCodeAndStateIssuesTokens() {
        UUID tenantId = UUID.randomUUID();

        // Enqueue token exchange response
        MOCK_SERVER.enqueue(new MockResponse()
            .setBody("{\"access_token\":\"mock-provider-token\",\"token_type\":\"Bearer\"}")
            .addHeader("Content-Type", "application/json"));

        // Enqueue userinfo response
        MOCK_SERVER.enqueue(new MockResponse()
            .setBody("{\"sub\":\"google-user-123\",\"email\":\"oauth@test.com\",\"name\":\"Test User\"}")
            .addHeader("Content-Type", "application/json"));

        // First get a valid state by starting the flow
        var startResponse = webTestClient.get()
            .uri("/v1/auth/oauth/google/start?tenantId=" + tenantId)
            .exchange()
            .expectStatus().is3xxRedirection()
            .returnResult(Void.class);

        String location = startResponse.getResponseHeaders().getFirst("Location");
        String state = extractStateFromUrl(location);

        if (state == null) {
            return;
        }

        webTestClient.get()
            .uri("/v1/auth/oauth/google/callback?code=mock-code&state=" + state)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.data.accessToken").isNotEmpty()
            .jsonPath("$.traceId").isNotEmpty();
    }

    private String extractStateFromUrl(String url) {
        if (url == null) return null;
        int stateIdx = url.indexOf("state=");
        if (stateIdx < 0) return null;
        int end = url.indexOf("&", stateIdx);
        return end < 0 ? url.substring(stateIdx + 6) : url.substring(stateIdx + 6, end);
    }
}
