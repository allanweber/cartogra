package io.cartogra.gateway.api;

import io.cartogra.gateway.AbstractGatewayIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class RateLimitIT extends AbstractGatewayIT {

    // Must match application-integration-test.yml app.rate-limit.auth-burst-capacity
    private static final int AUTH_BURST_CAPACITY = 5;

    @Test
    void authEndpointReturns429AfterBurstExhausted() throws Exception {
        List<Integer> statusCodes = new ArrayList<>();

        for (int i = 0; i <= AUTH_BURST_CAPACITY; i++) {
            int status = mockMvc.perform(post("/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"email":"ratelimit-%s@test.com","password":"password123","tenantId":"%s"}
                        """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andReturn().getResponse().getStatus();
            statusCodes.add(status);
        }

        assertThat(statusCodes).contains(429);
    }

    @Test
    void rateLimited429ResponseContainsErrorEnvelope() throws Exception {
        for (int i = 0; i < AUTH_BURST_CAPACITY; i++) {
            mockMvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"drain-%s@test.com","password":"password123","tenantId":"%s"}
                    """.formatted(UUID.randomUUID(), UUID.randomUUID())));
        }

        var result = mockMvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"over-limit@test.com","password":"password123","tenantId":"%s"}
                    """.formatted(UUID.randomUUID())))
            .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(429);
    }
}
