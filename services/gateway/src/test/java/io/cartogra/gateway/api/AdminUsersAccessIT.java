package io.cartogra.gateway.api;

import com.jayway.jsonpath.JsonPath;
import io.cartogra.gateway.AbstractGatewayIT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Writers list tenant users for the team member picker; every other admin user operation stays ADMIN-only. */
class AdminUsersAccessIT extends AbstractGatewayIT {

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "TEAM_OWNER", "MEMBER"})
    void writersCanListTenantUsers(String role) throws Exception {
        UUID tenantId = insertTenant();
        Cookie jwt = loginAs(tenantId, role);

        mockMvc.perform(get("/api/auth/admin/users").cookie(jwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void viewerCannotListTenantUsers() throws Exception {
        Cookie jwt = loginAs(insertTenant(), "VIEWER");

        mockMvc.perform(get("/api/auth/admin/users").cookie(jwt))
            .andExpect(status().isForbidden());
    }

    @Test
    void memberCannotChangeRoles() throws Exception {
        UUID tenantId = insertTenant();
        Cookie jwt = loginAs(tenantId, "MEMBER");
        UUID targetId = insertUser(tenantId, "target-" + UUID.randomUUID() + "@test.com", "VIEWER");

        mockMvc.perform(patch("/api/auth/admin/users/" + targetId + "/role")
                .cookie(jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\"}"))
            .andExpect(status().isForbidden());
    }

    private Cookie loginAs(UUID tenantId, String role) throws Exception {
        String email = "admin-users-" + UUID.randomUUID() + "@test.com";
        insertUser(tenantId, email, role);
        String body = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"Pass1!","tenantId":"%s"}
                    """.formatted(email, tenantId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return new Cookie("jwt", JsonPath.read(body, "$.data.accessToken"));
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

    private UUID insertUser(UUID tenantId, String email, String role) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO users (tenant_id, email, auth_provider, password_hash,
                               email_verified, roles, created_at, updated_at)
            VALUES (:tenantId, :email, 'local', :hash, true, ARRAY[:role]::text[], now(), now())
            RETURNING id
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("email", email)
                .addValue("role", role)
                .addValue("hash", passwordEncoder.encode("Pass1!")),
            UUID.class);
    }
}
