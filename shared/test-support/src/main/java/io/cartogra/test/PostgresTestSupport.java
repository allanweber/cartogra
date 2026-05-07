package io.cartogra.test;

import org.testcontainers.postgresql.PostgreSQLContainer;

public final class PostgresTestSupport {
    private PostgresTestSupport() {}

    public static final PostgreSQLContainer POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
        POSTGRES.withDatabaseName("cartogra_test");
        POSTGRES.withUsername("cartogra");
        POSTGRES.withPassword("cartogra");
        POSTGRES.start();
    }
}
