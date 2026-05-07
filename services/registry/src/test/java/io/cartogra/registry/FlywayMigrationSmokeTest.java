package io.cartogra.registry;

import io.cartogra.test.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FlywayMigrationSmokeTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "?currentSchema=registry");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void allMigrationsApplyCleanly() {
        Integer failedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM registry.flyway_schema_history WHERE success = false",
                Integer.class);
        assertThat(failedCount).isZero();
    }

    @Test
    void tenantsTableExists() {
        assertTableExists("tenants");
    }

    @Test
    void teamsTableExists() {
        assertTableExists("teams");
    }

    @Test
    void usersTableExists() {
        assertTableExists("users");
    }

    @Test
    void scmConnectionsTableExists() {
        assertTableExists("scm_connections");
    }

    private void assertTableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = 'registry' AND table_name = ?",
                Integer.class, tableName);
        assertThat(count).isEqualTo(1);
    }
}
