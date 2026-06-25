package io.cartogra.ingestion.infrastructure.jdbc;

import io.cartogra.ingestion.repository.AdvisoryLockRepository;
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
    public boolean tryAcquireXactLock(long key) {
        Boolean acquired = jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(:key)",
                new MapSqlParameterSource("key", key),
                Boolean.class);
        return Boolean.TRUE.equals(acquired);
    }
}
