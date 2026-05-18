package io.cartogra.gateway.api;

import io.cartogra.gateway.AbstractGatewayIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
    "app.rate-limit.enabled=true",
    "app.rate-limit.auth-replenish-rate=1",
    "app.rate-limit.auth-burst-capacity=5"
})
class RateLimitIT extends AbstractGatewayIT {

    private static final int AUTH_BURST_CAPACITY = 5;

    @Test
    void authEndpointReturns429AfterBurstExhausted() {
        List<Integer> statusCodes = new ArrayList<>();

        for (int i = 0; i <= AUTH_BURST_CAPACITY; i++) {
            int status = webTestClient.post()
                .uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                    "email", "ratelimit-" + UUID.randomUUID() + "@test.com",
                    "password", "password123",
                    "tenantId", UUID.randomUUID().toString()
                ))
                .exchange()
                .returnResult(String.class)
                .getStatus()
                .value();
            statusCodes.add(status);
        }

        // The last request should be rate-limited
        assertThat(statusCodes).contains(429);
    }

    @Test
    void rateLimited429ResponseContainsErrorEnvelope() {
        // Exhaust the burst
        for (int i = 0; i < AUTH_BURST_CAPACITY; i++) {
            webTestClient.post()
                .uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                    "email", "drain-" + UUID.randomUUID() + "@test.com",
                    "password", "password123",
                    "tenantId", UUID.randomUUID().toString()
                ))
                .exchange();
        }

        // This request should be rate-limited with a proper error envelope
        webTestClient.post()
            .uri("/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "email", "over-limit@test.com",
                "password", "password123",
                "tenantId", UUID.randomUUID().toString()
            ))
            .exchange()
            .expectStatus().isEqualTo(429)
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("RATE_LIMITED")
            .jsonPath("$.traceId").isNotEmpty();
    }
}
