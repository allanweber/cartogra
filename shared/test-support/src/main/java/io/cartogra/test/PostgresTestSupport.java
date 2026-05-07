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
        container.start();
        POSTGRES = container;
    }
}
