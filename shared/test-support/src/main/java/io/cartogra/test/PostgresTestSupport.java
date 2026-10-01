package io.cartogra.test;

import org.testcontainers.postgresql.PostgreSQLContainer;

public final class PostgresTestSupport {
    private PostgresTestSupport() {}

    public static final PostgreSQLContainer POSTGRES;

    static {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:16-alpine");
        container.withDatabaseName("cartogra_test");
        container.withUsername("cartogra");
        container.withPassword("cartogra");
        container.withReuse(true);
        // Every distinct @DynamicPropertySource value (e.g. a random Kafka consumer-group-id)
        // gives Spring's test context cache a new, never-evicted ApplicationContext for the
        // life of the JVM, each with its own HikariCP pool (default max 10). Across a growing
        // number of @SpringBootTest IT classes sharing this one container, cumulative open
        // connections can exceed Postgres's default max_connections=100 ("sorry, too many
        // clients already") well before any single pool is near its own limit.
        container.withCommand("postgres", "-c", "max_connections=300");
        container.start();
        POSTGRES = container;
    }
}
