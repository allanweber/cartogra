package io.cartogra.registry;

import org.flywaydb.core.Flyway;
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
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=registry");
        registry.add("spring.datasource.username", () -> PostgresTestSupport.APP_USER);
        registry.add("spring.datasource.password", () -> PostgresTestSupport.APP_PASSWORD);
        registry.add("spring.flyway.user", () -> PostgresTestSupport.OWNER_USER);
        registry.add("spring.flyway.password", () -> PostgresTestSupport.OWNER_PASSWORD);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    /** The app role cannot read the history table, so the check uses Flyway, which connects as the owner. */
    @Test
    void allMigrationsApplyCleanly() {
        assertThat(flyway.info().applied()).isNotEmpty()
                .allSatisfy(migration -> assertThat(migration.getState().isFailed()).isFalse());
        assertThat(flyway.info().pending()).isEmpty();
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
    void servicesTableExists() {
        assertTableExists("services");
    }

    @Test
    void servicesHistoryTableExists() {
        assertTableExists("services_history");
    }

    private void assertTableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = 'registry' AND table_name = ?",
                Integer.class, tableName);
        assertThat(count).isEqualTo(1);
    }
}
