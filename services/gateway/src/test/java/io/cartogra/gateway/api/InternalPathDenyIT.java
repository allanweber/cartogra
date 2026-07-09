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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A downstream service's context-path can accidentally expose its own internal,
 * service-to-service-only endpoints (e.g. registry's plan-limits lookup) under the
 * Gateway's general /api/v1/registry/** proxy route. This must be denied before it
 * ever reaches Spring Cloud Gateway's routing — including for an authenticated user
 * of a different tenant, which is the actual cross-tenant exploit path.
 */
class InternalPathDenyIT extends AbstractGatewayIT {

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void unauthenticatedRequestToDownstreamInternalPathIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/registry/internal/plan-limits/" + UUID.randomUUID()))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void authenticatedRequestToDownstreamInternalPathIsForbidden() throws Exception {
        UUID tenantId = insertTenant();
        String email = "internal-deny-" + UUID.randomUUID() + "@test.com";
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

        // An authenticated user with a valid role for THEIR OWN tenant must still be
        // denied — this endpoint takes tenantId from the path with no ownership check,
        // so reachability via the Gateway at all is the vulnerability, not the role.
        mockMvc.perform(get("/api/v1/registry/internal/plan-limits/" + UUID.randomUUID()).cookie(jwtCookie))
            .andExpect(status().isForbidden());
    }

    private UUID insertTenant() {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO tenants (id, tenant_id, name, slug, plan_id) VALUES (:id, :tid, :name, :slug, (SELECT id FROM billing_plans WHERE slug = 'free'))",
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
