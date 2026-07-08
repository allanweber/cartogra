package io.cartogra.gateway.api;

import com.jayway.jsonpath.JsonPath;
import io.cartogra.gateway.AbstractGatewayIT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RateLimitIT extends AbstractGatewayIT {

    // Must match application-integration-test.yml app.rate-limit.auth-burst-capacity
    private static final int AUTH_BURST_CAPACITY = 5;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void authEndpointReturns429AfterBurstExhausted() throws Exception {
        List<Integer> statusCodes = new ArrayList<>();

        for (int i = 0; i <= AUTH_BURST_CAPACITY; i++) {
            int status = mockMvc.perform(post("/api/auth/login")
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
    void rateLimited429ResponseContainsErrorEnvelopeAndRetryAfter_unauthenticatedPath() throws Exception {
        for (int i = 0; i < AUTH_BURST_CAPACITY; i++) {
            mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"drain-%s@test.com","password":"password123","tenantId":"%s"}
                    """.formatted(UUID.randomUUID(), UUID.randomUUID())));
        }

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"over-limit@test.com","password":"password123","tenantId":"%s"}
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(header().exists("Retry-After"));
    }

    @Test
    void rateLimited429ResponseContainsErrorEnvelopeAndRetryAfter_authenticatedPath() throws Exception {
        UUID tenantId = insertTenant();
        String email = "tenant-ratelimit-" + UUID.randomUUID() + "@test.com";
        insertVerifiedUser(tenantId, email, "Pass1!");

        String loginBody = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"Pass1!","tenantId":"%s"}
                    """.formatted(email, tenantId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String accessToken = JsonPath.read(loginBody, "$.data.accessToken");
        Cookie jwtCookie = new Cookie("jwt", accessToken);

        // Login itself is unauthenticated (no JWT yet), so it draws from the IP bucket,
        // not this tenant's bucket -- the full AUTH_BURST_CAPACITY is still available here.
        for (int i = 0; i < AUTH_BURST_CAPACITY; i++) {
            mockMvc.perform(get("/api/auth/userinfo").cookie(jwtCookie));
        }

        mockMvc.perform(get("/api/auth/userinfo").cookie(jwtCookie))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(header().exists("Retry-After"));
    }

    private UUID insertTenant() {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO tenants (id, tenant_id, name, slug) VALUES (:id, :tid, :name, :slug)",
            new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("tid", tenantId)
                .addValue("name", "Test Tenant")
                .addValue("slug", "test-tenant-" + tenantId));
        return tenantId;
    }

    private void insertVerifiedUser(UUID tenantId, String email, String plainPassword) {
        jdbcTemplate.update("""
            INSERT INTO users (tenant_id, email, auth_provider, password_hash,
                               email_verified, roles, created_at, updated_at)
            VALUES (:tenantId, :email, 'local', :hash, true, '{ADMIN}', now(), now())
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("email", email)
                .addValue("hash", passwordEncoder.encode(plainPassword)));
    }
}
