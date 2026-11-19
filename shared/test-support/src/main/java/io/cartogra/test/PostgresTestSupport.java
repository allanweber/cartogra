package io.cartogra.test;

import org.testcontainers.postgresql.PostgreSQLContainer;

public final class PostgresTestSupport {
    private PostgresTestSupport() {}

    public static final PostgreSQLContainer POSTGRES;

    /** The application role: not the owner, NOBYPASSRLS. Use it for {@code spring.datasource}. */
    public static final String APP_USER = PostgresRoles.APP_USER;
    public static final String APP_PASSWORD = PostgresRoles.APP_PASSWORD;

    /** The owner of the schemas. Use it for {@code spring.flyway}. */
    public static final String OWNER_USER = PostgresRoles.OWNER_USER;
    public static final String OWNER_PASSWORD = PostgresRoles.OWNER_PASSWORD;

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
        PostgresRoles.create(container);
        POSTGRES = container;
    }
}
