package io.cartogra.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cartogra.web.tenant.TenantContext;
import io.cartogra.web.tenant.TenantDb;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared checks for the RLS ITs of each service. Call them from a test method with {@link NoTenantScope}, so
 * that the scope is the one that the checks set.
 */
public final class RlsChecks {

    private RlsChecks() {
    }

    /** The application connects as a role that the policies apply to. */
    public static void assertAppRoleIsSubjectToRls(JdbcTemplate jdbc) {
        Map<String, Object> role = jdbc.queryForMap(
                "SELECT current_user AS name, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");
        assertThat(role.get("name")).isEqualTo(PostgresRoles.APP_USER);
        assertThat(role.get("rolsuper")).isEqualTo(false);
        assertThat(role.get("rolbypassrls")).isEqualTo(false);
    }

    /**
     * The application role can read and write each base table and use each sequence of the current schema,
     * and it has no right on the Flyway history.
     */
    public static void assertAppRoleHasDmlOnEveryTable(JdbcTemplate jdbc) {
        List<String> missing = jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                WHERE schemaname = current_schema() AND tablename NOT LIKE 'flyway_schema_history%'
                  AND NOT has_table_privilege(current_user, quote_ident(schemaname) || '.' || quote_ident(tablename),
                                              'SELECT, INSERT, UPDATE, DELETE')
                UNION ALL
                SELECT sequence_name FROM information_schema.sequences
                WHERE sequence_schema = current_schema()
                  AND NOT has_sequence_privilege(current_user,
                          quote_ident(sequence_schema) || '.' || quote_ident(sequence_name), 'USAGE')
                """, String.class);
        assertThat(missing).as("tables and sequences that the application role cannot use").isEmpty();

        List<String> history = jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                WHERE schemaname = current_schema() AND tablename LIKE 'flyway_schema_history%'
                  AND has_table_privilege(current_user, quote_ident(schemaname) || '.' || quote_ident(tablename),
                                          'SELECT, INSERT, UPDATE, DELETE, TRUNCATE')
                """, String.class);
        assertThat(history).as("Flyway history tables that the application role can use").isEmpty();
    }

    /** Each base table with a tenant_id column in the current schema has RLS enabled and forced. */
    public static void assertEveryTenantTableForcesRls(JdbcTemplate jdbc) {
        List<String> tenantTables = jdbc.queryForList("""
                SELECT c.table_name
                FROM information_schema.columns c
                JOIN information_schema.tables t
                  ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = current_schema() AND c.column_name = 'tenant_id'
                  AND t.table_type = 'BASE TABLE'
                """, String.class);
        List<String> notForced = jdbc.queryForList("""
                SELECT c.relname
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = current_schema() AND c.relkind = 'r'
                  AND c.relname = ANY (?::text[])
                  AND NOT (c.relrowsecurity AND c.relforcerowsecurity)
                """, String.class, (Object) tenantTables.toArray(String[]::new));
        assertThat(tenantTables).isNotEmpty();
        assertThat(notForced).as("tenant tables without ENABLE + FORCE ROW LEVEL SECURITY").isEmpty();
    }

    /**
     * Seeds one row for each of two tenants, then proves that a query without a tenant_id filter sees only the
     * bound tenant, that no scope sees no rows, and that a row of another tenant cannot be written.
     *
     * @param insertSql an INSERT with one {@code ?} parameter: the tenant_id
     */
    public static void assertIsolatesTenants(JdbcTemplate jdbc, String table, String insertSql) {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        TenantDb.runAsSystem(() -> {
            jdbc.update(insertSql, tenantA);
            jdbc.update(insertSql, tenantB);
        });

        List<UUID> visibleToA = TenantContext.callAs(tenantA,
                () -> jdbc.queryForList("SELECT DISTINCT tenant_id FROM " + table, UUID.class));
        assertThat(visibleToA).containsExactly(tenantA);

        Integer visibleWithoutScope = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        assertThat(visibleWithoutScope).isZero();

        List<UUID> visibleToSystem = TenantDb.asSystem(
                () -> jdbc.queryForList("SELECT DISTINCT tenant_id FROM " + table, UUID.class));
        assertThat(visibleToSystem).contains(tenantA, tenantB);

        assertThatThrownBy(() -> TenantContext.runAs(tenantA, () -> jdbc.update(insertSql, tenantB)))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
    }
}
