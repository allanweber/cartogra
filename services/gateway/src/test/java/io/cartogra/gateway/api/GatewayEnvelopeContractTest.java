package io.cartogra.gateway.api;

import io.cartogra.gateway.AbstractGatewayIT;
import io.cartogra.test.OpenApiContractValidator;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class GatewayEnvelopeContractTest extends AbstractGatewayIT {

    // Must match application-integration-test.yml app.rate-limit.auth-burst-capacity
    private static final int AUTH_BURST_CAPACITY = 5;

    private final OpenApiContractValidator contractValidator =
            new OpenApiContractValidator("gateway-errors.openapi.yaml");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void unauthenticatedRequestReturns401ConformingToSpec() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/userinfo"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertConformsToSpec("GET", "/api/auth/userinfo", result);
    }

    @Test
    void rateLimitedRequestReturns429ConformingToSpec() throws Exception {
        for (int i = 0; i < AUTH_BURST_CAPACITY; i++) {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"drain-429-%s@test.com","password":"pw"}
                            """.formatted(UUID.randomUUID())));
        }

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"over-limit-429@test.com","password":"pw"}
                                """))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        assertConformsToSpec("POST", "/api/auth/login", result);
    }

    @Test
    void loginValidationErrorReturns400ConformingToSpec() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"x"}
                                """))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertConformsToSpec("POST", "/api/auth/login", result);
    }

    private void assertConformsToSpec(String method, String path, MvcResult result) throws Exception {
        MockHttpServletResponse response = result.getResponse();
        Map<String, String> headers = response.getHeaderNames().stream()
                .collect(Collectors.toMap(h -> h, response::getHeader));
        String body = response.getContentAsString(StandardCharsets.UTF_8);

        contractValidator.assertResponse(method, path, response.getStatus(), body, headers);

        String traceId = objectMapper.readValue(body, TraceResponse.class).traceId();
        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo(traceId);
    }

    // Minimal record to extract traceId from any error envelope without using deprecated JsonNode methods.
    private record TraceResponse(String traceId) {}
}
