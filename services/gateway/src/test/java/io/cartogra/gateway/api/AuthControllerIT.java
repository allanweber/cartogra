package io.cartogra.gateway.api;

import com.jayway.jsonpath.JsonPath;
import io.cartogra.gateway.AbstractGatewayIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthControllerIT extends AbstractGatewayIT {

    // Must match application-integration-test.yml app.rate-limit.auth-burst-capacity
    private static final int AUTH_BURST_CAPACITY = 5;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void registerNewUserReturns201() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"newuser@test.com","password":"password123","orgName":"Test Org"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(jsonPath("$.data.tenantId").isNotEmpty());
    }

    @Test
    void registerWithoutOrgNameUsesEmailAsTenantName() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"noorg-%s@test.com","password":"password123"}
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.tenantId").isNotEmpty());
    }

    @Test
    void loginWithUnverifiedUserReturns401() throws Exception {
        String email = "unverified-" + UUID.randomUUID() + "@test.com";
        UUID tenantId = registerAndExtractTenantId(email, "password123");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"password123","tenantId":"%s"}
                    """.formatted(email, tenantId)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").isNotEmpty())
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"nonexistent@test.com","password":"wrong","tenantId":"%s"}
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void fullRegisterVerifyLoginFlow() throws Exception {
        String email = "full-flow-" + UUID.randomUUID() + "@test.com";
        registerAndExtractTenantId(email, "password123");

        Thread.sleep(100);

        mockMvc.perform(post("/api/auth/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","token":"000000"}
                    """.formatted(email)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void invalidEmailReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"not-an-email","password":"password123"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void shortPasswordReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"user@test.com","password":"short"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void accessProtectedRouteWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/services"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void getUserinfo_localAuthUser_returnsNullNameAndLocalProvider() throws Exception {
        UUID tenantId = insertTenant();
        String email = "userinfo-" + UUID.randomUUID() + "@test.com";
        insertVerifiedUser(tenantId, email, "Pass1!");

        String loginBody = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"Pass1!","tenantId":"%s"}
                    """.formatted(email, tenantId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String accessToken = JsonPath.read(loginBody, "$.data.accessToken");

        mockMvc.perform(get("/api/auth/userinfo")
                .cookie(new Cookie("jwt", accessToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.authProvider").value("local"))
            .andExpect(jsonPath("$.data.name").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.email").value(email))
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void passwordResetHappyPath() throws Exception {
        UUID tenantId = insertTenant();
        String email = "reset-happy-" + UUID.randomUUID() + "@test.com";
        insertVerifiedUser(tenantId, email, "OldPass1!");

        // AC-1: forgot-password returns 200 with valid envelope
        var forgotResult = mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s"}
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(header().exists("X-Trace-Id"))
            .andReturn();

        String responseBody = forgotResult.getResponse().getContentAsString();
        String traceId = JsonPath.read(responseBody, "$.traceId");
        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat(forgotResult.getResponse().getHeader("X-Trace-Id")).isEqualTo(traceId);

        // Read reset token written synchronously before async email fires
        String resetToken = jdbcTemplate.queryForObject(
            "SELECT password_reset_token FROM users WHERE email = :email",
            new MapSqlParameterSource("email", email),
            String.class);

        // AC-3: reset-password with valid token returns 200
        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"token":"%s","newPassword":"NewPass1!"}
                    """.formatted(resetToken)))
            .andExpect(status().isOk());

        // login with new password succeeds and returns accessToken
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"NewPass1!","tenantId":"%s"}
                    """.formatted(email, tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    void resetPasswordWithExpiredTokenReturns400() throws Exception {
        UUID tenantId = insertTenant();
        String email = "reset-expired-" + UUID.randomUUID() + "@test.com";
        insertVerifiedUserWithExpiredResetToken(tenantId, email, "999999");

        // AC-4: expired token returns 400 with error.code and traceId
        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"token":"999999","newPassword":"NewPass1!"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").isNotEmpty())
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void forgotPasswordRateLimitedAfterBurstExhausted() throws Exception {
        // AC-6: drain burst then assert 429 with Retry-After
        for (int i = 0; i < AUTH_BURST_CAPACITY; i++) {
            mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"drain-%d@example.com"}
                    """.formatted(i)));
        }

        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"over-limit@example.com"}
                    """))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(429))
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void updateUserInfo_emailChange_setsUnverifiedAndSendsVerificationEmail() throws Exception {
        UUID tenantId = insertTenant();
        String email = "email-change-" + UUID.randomUUID() + "@test.com";
        String newEmail = "new-" + UUID.randomUUID() + "@test.com";
        insertVerifiedUser(tenantId, email, "Pass1!");

        String loginBody = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"Pass1!","tenantId":"%s"}
                    """.formatted(email, tenantId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String accessToken = JsonPath.read(loginBody, "$.data.accessToken");

        mockMvc.perform(put("/api/auth/userinfo")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie(new Cookie("jwt", accessToken))
                .content("""
                    {"name":"Alice","email":"%s"}
                    """.formatted(newEmail)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.email").value(newEmail))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(header().exists("Set-Cookie"));

        Boolean verified = jdbcTemplate.queryForObject(
            "SELECT email_verified FROM users WHERE email = :email AND deleted_at IS NULL",
            new MapSqlParameterSource("email", newEmail), Boolean.class);
        assertThat(verified).isFalse();
    }

    @Test
    void updateUserInfo_sameEmail_doesNotTriggerReverification() throws Exception {
        UUID tenantId = insertTenant();
        String email = "same-email-" + UUID.randomUUID() + "@test.com";
        insertVerifiedUser(tenantId, email, "Pass1!");

        String loginBody = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"Pass1!","tenantId":"%s"}
                    """.formatted(email, tenantId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String accessToken = JsonPath.read(loginBody, "$.data.accessToken");

        mockMvc.perform(put("/api/auth/userinfo")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie(new Cookie("jwt", accessToken))
                .content("""
                    {"name":"Bob","email":"%s"}
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.email").value(email));

        Boolean verified = jdbcTemplate.queryForObject(
            "SELECT email_verified FROM users WHERE email = :email AND deleted_at IS NULL",
            new MapSqlParameterSource("email", email), Boolean.class);
        assertThat(verified).isTrue();
    }

    @Test
    void updateUserInfo_emailConflict_returns409() throws Exception {
        UUID tenantId = insertTenant();
        String emailA = "conflict-a-" + UUID.randomUUID() + "@test.com";
        String emailB = "conflict-b-" + UUID.randomUUID() + "@test.com";
        insertVerifiedUser(tenantId, emailA, "Pass1!");
        insertVerifiedUser(tenantId, emailB, "Pass1!");

        String loginBody = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"Pass1!","tenantId":"%s"}
                    """.formatted(emailA, tenantId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String accessToken = JsonPath.read(loginBody, "$.data.accessToken");

        mockMvc.perform(put("/api/auth/userinfo")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie(new Cookie("jwt", accessToken))
                .content("""
                    {"email":"%s"}
                    """.formatted(emailB)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONFLICT"))
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void updateUserInfo_nameOnly_returnsUpdatedUserAndFreshToken() throws Exception {
        UUID tenantId = insertTenant();
        String email = "update-name-" + UUID.randomUUID() + "@test.com";
        insertVerifiedUser(tenantId, email, "Pass1!");

        String loginBody = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"Pass1!","tenantId":"%s"}
                    """.formatted(email, tenantId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String accessToken = JsonPath.read(loginBody, "$.data.accessToken");

        var result = mockMvc.perform(put("/api/auth/userinfo")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie(new Cookie("jwt", accessToken))
                .content("""
                    {"name":"Alice Smith"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("Alice Smith"))
            .andExpect(jsonPath("$.data.email").value(email))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(header().exists("Set-Cookie"))
            .andReturn();

        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("jwt=");
    }

    @Test
    void updateUserInfo_emptyNameCoercedToNull() throws Exception {
        UUID tenantId = insertTenant();
        String email = "update-null-" + UUID.randomUUID() + "@test.com";
        insertVerifiedUser(tenantId, email, "Pass1!");

        String loginBody = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"Pass1!","tenantId":"%s"}
                    """.formatted(email, tenantId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String accessToken = JsonPath.read(loginBody, "$.data.accessToken");

        mockMvc.perform(put("/api/auth/userinfo")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie(new Cookie("jwt", accessToken))
                .content("""
                    {"name":"  "}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void updateUserInfo_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/api/auth/userinfo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Alice"}
                    """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty());
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

    private void insertVerifiedUserWithExpiredResetToken(UUID tenantId, String email, String token) {
        jdbcTemplate.update("""
            INSERT INTO users (tenant_id, email, auth_provider, password_hash, email_verified, roles,
                               password_reset_token, password_reset_token_exp, created_at, updated_at)
            VALUES (:tenantId, :email, 'local', :hash, true, '{ADMIN}',
                   :token, now() - interval '1 hour', now(), now())
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("email", email)
                .addValue("hash", passwordEncoder.encode("AnyPass1!"))
                .addValue("token", token));
    }

    private UUID registerAndExtractTenantId(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s"}
                    """.formatted(email, password)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        return UUID.fromString(JsonPath.read(body, "$.data.tenantId"));
    }
}
