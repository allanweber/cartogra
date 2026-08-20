package io.cartogra.topology;

import io.cartogra.test.PostgresTestSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for topology integration tests. Shares one Testcontainers Postgres instance
 * (started once per JVM via {@link PostgresTestSupport}) across every IT class, mirroring
 * registry's IT setup.
 *
 * <p>The graph-view refresh scheduler's own tick is pushed out to an hour so its background
 * {@code REFRESH MATERIALIZED VIEW} never races with a test calling {@code refresh()} or the
 * advisory lock directly.
 */
@SpringBootTest(properties = "topology.graph-view.refresh-interval=PT1H")
public abstract class AbstractTopologyIT {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=topology");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
    }
}
