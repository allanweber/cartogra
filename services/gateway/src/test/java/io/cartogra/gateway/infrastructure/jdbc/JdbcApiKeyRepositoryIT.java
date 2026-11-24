package io.cartogra.gateway.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.cartogra.gateway.AbstractGatewayIT;
import io.cartogra.gateway.domain.ApiKey;
import io.cartogra.gateway.domain.ApiKeyAuthenticator;
import io.cartogra.gateway.repository.ApiKeyRepository;
import io.cartogra.test.NoTenantScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** S-12. The application connects as cartogra_app, so RLS applies and the system scope is proven. */
class JdbcApiKeyRepositoryIT extends AbstractGatewayIT {

    @Autowired
    private ApiKeyRepository repository;

    @Autowired
    private ApiKeyAuthenticator authenticator;

    private UUID tenantId;
    private UUID userId;

    @BeforeEach
    void seedUser() {
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();
        superuserJdbc().update("""
            INSERT INTO users (id, tenant_id, email, auth_provider) VALUES (:id, :tenantId, :email, 'local')
            """, new MapSqlParameterSource().addValue("id", userId).addValue("tenantId", tenantId)
            .addValue("email", userId + "@test.com"));
    }

    private UUID seed(String raw, Instant expiresAt, Instant revokedAt) {
        UUID id = UUID.randomUUID();
        superuserJdbc().update("""
            INSERT INTO api_keys (id, tenant_id, name, key_hash, key_prefix, scopes, expires_at, revoked_at, created_by)
            VALUES (:id, :tenantId, 'ci', :hash, 'ctg_test', '{ci:check}', :expiresAt, :revokedAt, :createdBy)
            """, new MapSqlParameterSource().addValue("id", id).addValue("tenantId", tenantId)
            .addValue("hash", ApiKeyAuthenticator.hash(raw))
            .addValue("expiresAt", expiresAt == null ? null : Timestamp.from(expiresAt))
            .addValue("revokedAt", revokedAt == null ? null : Timestamp.from(revokedAt))
            .addValue("createdBy", userId));
        return id;
    }

    private Timestamp lastUsed(UUID id) {
        return superuserJdbc().queryForObject("SELECT last_used_at FROM api_keys WHERE id = :id",
            new MapSqlParameterSource("id", id), Timestamp.class);
    }

    @Test
    void activeKeyIsFoundWithItsTenantAndScopes() {
        UUID id = seed("ctg_active", null, null);

        ApiKey key = authenticator.authenticate("ctg_active").orElseThrow();

        assertThat(key.id()).isEqualTo(id);
        assertThat(key.tenantId()).isEqualTo(tenantId);
        assertThat(key.scopes()).containsExactly("ci:check");
    }

    @Test
    void keyWithAFutureExpiryIsFound() {
        seed("ctg_future", Instant.now().plus(1, ChronoUnit.DAYS), null);

        assertThat(authenticator.authenticate("ctg_future")).isPresent();
    }

    @Test
    void revokedKeyIsNotFound() {
        seed("ctg_revoked", null, Instant.now().minusSeconds(60));

        assertThat(authenticator.authenticate("ctg_revoked")).isEmpty();
    }

    @Test
    void expiredKeyIsNotFound() {
        seed("ctg_expired", Instant.now().minusSeconds(60), null);

        assertThat(authenticator.authenticate("ctg_expired")).isEmpty();
    }

    @Test
    @NoTenantScope
    void lookupNeedsTheSystemScopeBecauseOfRls() {
        seed("ctg_scope", null, null);

        assertThat(repository.findActiveByHash(ApiKeyAuthenticator.hash("ctg_scope"))).isEmpty();
        assertThat(authenticator.authenticate("ctg_scope")).isPresent();
    }

    @Test
    void lastUsedIsSetOnceForTwoCallsInTenSeconds() {
        UUID id = seed("ctg_touch", null, null);
        assertThat(lastUsed(id)).isNull();

        authenticator.authenticate("ctg_touch");
        Timestamp first = lastUsed(id);
        assertThat(first).isNotNull();

        authenticator.authenticate("ctg_touch");
        assertThat(lastUsed(id)).isEqualTo(first);
    }
}
