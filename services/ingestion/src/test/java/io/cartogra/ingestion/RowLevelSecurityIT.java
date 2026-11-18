package io.cartogra.ingestion;

import io.cartogra.ingestion.infrastructure.kafka.SyncCommandConsumer;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@NoTenantScope
class RowLevelSecurityIT {

    @MockitoBean
    SyncCommandConsumer syncCommandConsumer;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=ingestion");
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
    void everyTenantTableForcesRls() {
        RlsChecks.assertEveryTenantTableForcesRls(jdbc);
    }

    @Test
    void aQueryWithoutATenantFilterSeesOnlyTheBoundTenant() {
        RlsChecks.assertIsolatesTenants(jdbc, "k8s_clusters", """
                INSERT INTO k8s_clusters (tenant_id, name, source, api_server_url)
                VALUES (?, 'rls', 'MANUAL', 'https://k8s.invalid')
                """);
    }
}
