package io.cartogra.gateway.api;

import io.cartogra.gateway.AbstractGatewayIT;
import io.cartogra.gateway.infrastructure.oauth.GitHubOAuthProvider;
import io.cartogra.gateway.infrastructure.oauth.OAuthProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OAuthControllerIT extends AbstractGatewayIT {

    @MockitoBean
    GitHubOAuthProvider githubOAuthProvider;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void stubGithubBase() {
        Mockito.lenient().when(githubOAuthProvider.providerName()).thenReturn("github");
        Mockito.lenient().when(githubOAuthProvider.buildAuthorizationUri(any(), any()))
            .thenReturn("https://github.com/login/oauth/authorize?state=test");
    }

    @Test
    void oauthStartRedirectsToProvider() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(get("/api/auth/oauth/google/start")
                .param("tenantId", tenantId.toString()))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", containsString("accounts.google.com")));
    }

    @Test
    void oauthStartWithGithubRedirectsToGithub() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(get("/api/auth/oauth/github/start")
                .param("tenantId", tenantId.toString()))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", containsString("github.com")));
    }

    @Test
    void oauthCallbackWithInvalidStateRedirectsToLoginWithError() throws Exception {
        mockMvc.perform(get("/api/auth/oauth/google/callback")
                .param("code", "authcode")
                .param("state", "invalid-state"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", containsString("error=oauth_failed")));
    }

    @Test
    void oauthCallback_newUser_seedsProviderName() throws Exception {
        String email = "gh-new-" + UUID.randomUUID() + "@test.com";
        String state = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set("oauth:state:" + state, "new");

        when(githubOAuthProvider.exchangeCodeForAccessToken(any(), any())).thenReturn("gh-token");
        when(githubOAuthProvider.fetchProfile("gh-token"))
            .thenReturn(new OAuthProfile("gh-123", email, "Jane GitHub"));

        mockMvc.perform(get("/api/auth/oauth/github/callback")
                .param("code", "authcode")
                .param("state", state))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", containsString("/dashboard")));

        String name = jdbc.queryForObject(
            "SELECT name FROM users WHERE email = :email AND deleted_at IS NULL",
            new MapSqlParameterSource("email", email), String.class);
        assertThat(name).isEqualTo("Jane GitHub");
    }

    @Test
    void oauthCallback_repeatLogin_doesNotOverwriteEditedName() throws Exception {
        String email = "gh-repeat-" + UUID.randomUUID() + "@test.com";

        // First login — creates the user with provider name
        String state1 = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set("oauth:state:" + state1, "new");
        when(githubOAuthProvider.exchangeCodeForAccessToken(any(), any())).thenReturn("gh-token1");
        when(githubOAuthProvider.fetchProfile("gh-token1"))
            .thenReturn(new OAuthProfile("gh-42", email, "Original Provider Name"));

        mockMvc.perform(get("/api/auth/oauth/github/callback")
                .param("code", "code1")
                .param("state", state1))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", containsString("/dashboard")));

        // User edits their name in Cartogra
        jdbc.update("UPDATE users SET name = :name WHERE email = :email AND deleted_at IS NULL",
            new MapSqlParameterSource().addValue("name", "My Custom Name").addValue("email", email));

        String tenantIdStr = jdbc.queryForObject(
            "SELECT tenant_id::text FROM users WHERE email = :email AND deleted_at IS NULL",
            new MapSqlParameterSource("email", email), String.class);

        // Second login — provider returns a different name
        String state2 = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set("oauth:state:" + state2, tenantIdStr);
        when(githubOAuthProvider.exchangeCodeForAccessToken(any(), any())).thenReturn("gh-token2");
        when(githubOAuthProvider.fetchProfile("gh-token2"))
            .thenReturn(new OAuthProfile("gh-42", email, "Updated Provider Name"));

        mockMvc.perform(get("/api/auth/oauth/github/callback")
                .param("code", "code2")
                .param("state", state2))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", containsString("/dashboard")));

        String name = jdbc.queryForObject(
            "SELECT name FROM users WHERE email = :email AND deleted_at IS NULL",
            new MapSqlParameterSource("email", email), String.class);
        assertThat(name).isEqualTo("My Custom Name");
    }

    @Test
    void oauthCallback_newTenant_doesNotBindToUnverifiedLocalAccount() throws Exception {
        String email = "unverified-new-" + UUID.randomUUID() + "@test.com";
        insertUnverifiedLocalUser(insertTenant(), email);

        String state = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set("oauth:state:" + state, "new");

        when(githubOAuthProvider.exchangeCodeForAccessToken(any(), any())).thenReturn("gh-token-new");
        when(githubOAuthProvider.fetchProfile("gh-token-new"))
            .thenReturn(new OAuthProfile("gh-attacker-new", email, "Attacker Controlled Name"));

        mockMvc.perform(get("/api/auth/oauth/github/callback")
                .param("code", "authcode")
                .param("state", state))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", containsString("error=oauth_failed")));

        String authProvider = jdbc.queryForObject(
            "SELECT auth_provider FROM users WHERE email = :email AND deleted_at IS NULL",
            new MapSqlParameterSource("email", email), String.class);
        assertThat(authProvider).isEqualTo("local");
    }

    @Test
    void oauthCallback_existingTenant_doesNotBindToUnverifiedLocalAccount() throws Exception {
        String email = "unverified-existing-" + UUID.randomUUID() + "@test.com";
        UUID tenantId = insertTenant();
        insertUnverifiedLocalUser(tenantId, email);

        String state = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set("oauth:state:" + state, tenantId.toString());

        when(githubOAuthProvider.exchangeCodeForAccessToken(any(), any())).thenReturn("gh-token-existing");
        when(githubOAuthProvider.fetchProfile("gh-token-existing"))
            .thenReturn(new OAuthProfile("gh-attacker-existing", email, "Attacker Controlled Name"));

        mockMvc.perform(get("/api/auth/oauth/github/callback")
                .param("code", "authcode")
                .param("state", state))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", containsString("error=oauth_failed")));

        String authProvider = jdbc.queryForObject(
            "SELECT auth_provider FROM users WHERE email = :email AND deleted_at IS NULL",
            new MapSqlParameterSource("email", email), String.class);
        assertThat(authProvider).isEqualTo("local");
    }

    private UUID insertTenant() {
        UUID tenantId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO tenants (id, tenant_id, name, slug, plan_id) VALUES (:id, :tid, :name, :slug, (SELECT id FROM billing_plans WHERE slug = 'free'))",
            new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("tid", tenantId)
                .addValue("name", "Test Tenant")
                .addValue("slug", "test-tenant-" + tenantId));
        return tenantId;
    }

    private void insertUnverifiedLocalUser(UUID tenantId, String email) {
        jdbc.update("""
            INSERT INTO users (tenant_id, email, auth_provider, password_hash,
                               email_verified, roles, created_at, updated_at)
            VALUES (:tenantId, :email, 'local', 'irrelevant-hash', false, '{ADMIN}', now(), now())
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("email", email));
    }
}
