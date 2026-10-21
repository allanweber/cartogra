package io.cartogra.gateway.infrastructure.jdbc;

import io.cartogra.gateway.AbstractGatewayIT;
import io.cartogra.gateway.domain.RefreshToken;
import io.cartogra.gateway.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRefreshTokenRepositoryIT extends AbstractGatewayIT {

    @Autowired
    private RefreshTokenRepository repository;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private UUID tenantId;
    private UUID userId;

    @BeforeEach
    void createUser() {
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO users (id, tenant_id, email, auth_provider)
            VALUES (:id, :tenantId, :email, 'local')
            """, new MapSqlParameterSource()
            .addValue("id", userId)
            .addValue("tenantId", tenantId)
            .addValue("email", userId + "@test.com"));
    }

    private RefreshToken login(String hash) {
        return repository.save(RefreshToken.issue(tenantId, userId, hash, Instant.now().plus(1, ChronoUnit.DAYS)));
    }

    private int rowsForUser() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = :userId",
            new MapSqlParameterSource("userId", userId), Integer.class);
        return count == null ? 0 : count;
    }

    private String hash(String seed) {
        return seed + ":" + userId;
    }

    @Test
    void repeatedRotationsKeepOneRowPerSession() {
        RefreshToken session = login(hash("h0"));

        for (int i = 1; i <= 5; i++) {
            assertThat(repository.rotate(hash("h" + (i - 1)), hash("h" + i), Instant.now().plus(1, ChronoUnit.DAYS)))
                .get().extracting(RefreshToken::id).isEqualTo(session.id());
        }

        assertThat(rowsForUser()).isEqualTo(1);
        RefreshToken current = repository.findActiveByHash(hash("h5")).orElseThrow();
        assertThat(current.previousHash()).isEqualTo(hash("h4"));
        assertThat(current.lastUsedAt()).isNotNull();
    }

    @Test
    void rotatedHashIsNoLongerActiveButResolvesAsPrevious() {
        RefreshToken session = login(hash("old"));
        repository.rotate(hash("old"), hash("new"), Instant.now().plus(1, ChronoUnit.DAYS));

        assertThat(repository.findActiveByHash(hash("old"))).isEmpty();
        assertThat(repository.findActiveByPreviousHash(hash("old")))
            .get().extracting(RefreshToken::id).isEqualTo(session.id());
    }

    @Test
    void rotatingAnAlreadyRotatedHashFails() {
        login(hash("old"));
        repository.rotate(hash("old"), hash("winner"), Instant.now().plus(1, ChronoUnit.DAYS));

        assertThat(repository.rotate(hash("old"), hash("loser"), Instant.now().plus(1, ChronoUnit.DAYS))).isEmpty();
        assertThat(repository.findActiveByHash(hash("winner"))).isPresent();
    }

    @Test
    void revokedSessionCannotRotateOrResolveAsPrevious() {
        RefreshToken session = login(hash("old"));
        repository.rotate(hash("old"), hash("new"), Instant.now().plus(1, ChronoUnit.DAYS));
        repository.revoke(session.id());

        assertThat(repository.rotate(hash("new"), hash("next"), Instant.now().plus(1, ChronoUnit.DAYS))).isEmpty();
        assertThat(repository.findActiveByPreviousHash(hash("old"))).isEmpty();
    }
}
