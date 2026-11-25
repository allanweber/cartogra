package io.cartogra.registry;

import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.NoTenantScope;
import io.cartogra.test.PostgresTestSupport;
import io.cartogra.test.RlsChecks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "registry.health.probe-interval=PT999H")
@NoTenantScope
class RowLevelSecurityIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=registry");
        registry.add("spring.datasource.username", () -> PostgresTestSupport.APP_USER);
        registry.add("spring.datasource.password", () -> PostgresTestSupport.APP_PASSWORD);
        registry.add("spring.flyway.user", () -> PostgresTestSupport.OWNER_USER);
        registry.add("spring.flyway.password", () -> PostgresTestSupport.OWNER_PASSWORD);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theAppRoleIsSubjectToRls() {
        RlsChecks.assertAppRoleIsSubjectToRls(jdbc);
    }

    @Test
    void theAppRoleCanUseEveryTableButNotTheMigrationHistory() {
        RlsChecks.assertAppRoleHasDmlOnEveryTable(jdbc);
    }

    @Test
    void everyTenantTableForcesRls() {
        RlsChecks.assertEveryTenantTableForcesRls(jdbc);
    }

    @Test
    void aQueryWithoutATenantFilterSeesOnlyTheBoundTenant() {
        RlsChecks.assertIsolatesTenants(jdbc, "teams",
                "INSERT INTO teams (tenant_id, name) VALUES (?, 'rls-' || gen_random_uuid())");
    }

    @Test
    void apiKeysAreIsolatedByTenant() {
        RlsChecks.assertIsolatesTenants(jdbc, "api_keys", """
                WITH creator AS (
                    INSERT INTO users (tenant_id, email, auth_provider, roles)
                    VALUES (?, 'rls-' || gen_random_uuid() || '@cartogra.test', 'local', ARRAY['ADMIN'])
                    RETURNING id, tenant_id
                )
                INSERT INTO api_keys (tenant_id, name, key_hash, key_prefix, scopes, created_by)
                SELECT tenant_id, 'rls', md5(gen_random_uuid()::text) || md5(gen_random_uuid()::text), 'cgk_xxxxxxxx',
                       ARRAY['ci:check'], id
                FROM creator
                """);
    }
}
