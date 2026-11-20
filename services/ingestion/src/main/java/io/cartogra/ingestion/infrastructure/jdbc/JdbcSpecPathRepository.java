package io.cartogra.ingestion.infrastructure.jdbc;

import io.cartogra.ingestion.repository.SpecPathRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcSpecPathRepository implements SpecPathRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSpecPathRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> findCustomPaths(UUID tenantId) {
        String sql = """
                SELECT paths FROM tenant_spec_paths
                WHERE tenant_id = :tenantId AND deleted_at IS NULL
                """;
        return jdbc.query(sql, new MapSqlParameterSource("tenantId", tenantId),
                        (rs, _) -> toList(rs.getArray("paths")))
                .stream().findFirst().orElse(List.of());
    }

    @Override
    public void saveCustomPaths(UUID tenantId, List<String> paths) {
        String sql = """
                INSERT INTO tenant_spec_paths (tenant_id, paths)
                VALUES (:tenantId, :paths)
                ON CONFLICT (tenant_id) DO UPDATE SET
                    paths      = EXCLUDED.paths,
                    updated_at = now(),
                    deleted_at = NULL
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("paths", paths.toArray(String[]::new)));
    }

    private static List<String> toList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return List.copyOf(Arrays.asList((String[]) array.getArray()));
    }
}
