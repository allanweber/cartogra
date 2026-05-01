package io.cartogra.test;

import org.testcontainers.postgresql.PostgreSQLContainer;

public final class PostgresTestSupport {
    private PostgresTestSupport() {}

    @SuppressWarnings("resource")
    public static PostgreSQLContainer create() {
        return new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("cartogra_test").withUsername("cartogra").withPassword("cartogra")
            .withReuse(true);
    }
}
