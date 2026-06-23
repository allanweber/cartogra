package io.cartogra.registry.infrastructure.jdbc;

import io.cartogra.registry.repository.AdvisoryLockRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdvisoryLockRepository implements AdvisoryLockRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAdvisoryLockRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryAcquireLock(long key) {
        Boolean result = jdbc.queryForObject(
                "SELECT pg_try_advisory_lock(:key)",
                new MapSqlParameterSource("key", key),
                Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    @Override
    public void releaseLock(long key) {
        // pg_advisory_unlock returns boolean — use queryForObject, not update
        jdbc.queryForObject(
                "SELECT pg_advisory_unlock(:key)",
                new MapSqlParameterSource("key", key),
                Boolean.class);
    }
}
