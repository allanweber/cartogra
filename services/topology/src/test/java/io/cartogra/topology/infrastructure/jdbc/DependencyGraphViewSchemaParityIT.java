package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.AbstractTopologyIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code dependency_graph_edges} is a hand-maintained {@code SELECT} projection of
 * {@code dependencies} (see the comment header on {@code V003__create_dependency_graph_view.sql}),
 * not a generated mirror. Postgres already refuses to drop/rename a {@code dependencies}
 * column the view depends on, but it has no opinion on a column that's added to
 * {@code dependencies} and never projected into the view — that drift is silent. This test
 * forces every {@code dependencies} column into one of two buckets: projected into the view,
 * or named in {@link #INTENTIONALLY_EXCLUDED_COLUMNS} with a reason. Adding a column to
 * neither fails the build.
 */
class DependencyGraphViewSchemaParityIT extends AbstractTopologyIT {

    /**
     * dependencies columns the view deliberately omits, and why:
     * - id: the view's identity is the (source, target, type, protocol) tuple, not a row id.
     * - created_at / updated_at: bookkeeping timestamps graph traversals never filter or join on.
     * - deleted_at: the view's WHERE clause consumes this to select live rows; it's the
     *   mechanism, not a value graph queries read.
     */
    private static final Set<String> INTENTIONALLY_EXCLUDED_COLUMNS =
            Set.of("id", "created_at", "updated_at", "deleted_at");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyDependenciesColumnIsEitherInTheViewOrExplicitlyExcluded() {
        Set<String> dependenciesColumns = columnsOf("dependencies");
        Set<String> viewColumns = columnsOf("dependency_graph_edges");

        Set<String> unaccountedFor = dependenciesColumns.stream()
                .filter(column -> !viewColumns.contains(column))
                .filter(column -> !INTENTIONALLY_EXCLUDED_COLUMNS.contains(column))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(unaccountedFor)
                .as("""
                        dependencies has columns that dependency_graph_edges neither projects nor \
                        explicitly excludes. Either add them to the view's SELECT (and re-create it, \
                        Postgres materialized views can't ALTER their query) or add them to \
                        DependencyGraphViewSchemaParityIT#INTENTIONALLY_EXCLUDED_COLUMNS with a reason.\
                        """)
                .isEmpty();
    }

    @Test
    void theViewProjectsNoColumnDependenciesDoesNotHave() {
        Set<String> dependenciesColumns = columnsOf("dependencies");
        Set<String> viewColumns = columnsOf("dependency_graph_edges");

        assertThat(dependenciesColumns).containsAll(viewColumns);
    }

    /**
     * information_schema.columns omits materialized views (relkind 'm') — they aren't
     * "views" by the SQL-standard definition it implements. pg_attribute covers every
     * relkind uniformly, so it's used for both the table and the materialized view here.
     */
    private Set<String> columnsOf(String relationName) {
        List<String> columns = jdbc.queryForList("""
                SELECT a.attname
                FROM pg_attribute a
                JOIN pg_class c ON a.attrelid = c.oid
                JOIN pg_namespace n ON c.relnamespace = n.oid
                WHERE n.nspname = 'topology' AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
                """, String.class, relationName);
        assertThat(columns).as("table/view '%s' should exist", relationName).isNotEmpty();
        return Set.copyOf(columns);
    }
}
