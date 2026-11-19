package io.cartogra.gateway;

import com.redis.testcontainers.RedisContainer;
import io.cartogra.test.PostgresRoles;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@SpringBootTest
@ActiveProfiles("integration-test")
public abstract class AbstractGatewayIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private StringRedisTemplate redis;

    protected MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
        var keys = redis.keys("rate_limit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // Containers started once for the whole JVM — prevents stale Spring context cache issues
    // when multiple test classes share the same @DynamicPropertySource method.
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("cartogra_test")
        .withUsername("cartogra")
        .withPassword("cartogra");

    @SuppressWarnings("resource")
    static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");

    static {
        POSTGRES.start();
        PostgresRoles.create(POSTGRES);
        createSchema();
        REDIS.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> PostgresRoles.APP_USER);
        registry.add("spring.datasource.password", () -> PostgresRoles.APP_PASSWORD);
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    /** Connects as the superuser: RLS does not apply, so a test can seed and read rows of any tenant. */
    protected static NamedParameterJdbcTemplate superuserJdbc() {
        return new NamedParameterJdbcTemplate(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    /** Runs the test schema as the superuser: the application role has DML grants only. */
    private static void createSchema() {
        POSTGRES.copyFileToContainer(MountableFile.forClasspathResource("db/test-schema.sql"), "/tmp/test-schema.sql");
        try {
            var result = POSTGRES.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-U", POSTGRES.getUsername(),
                "-d", POSTGRES.getDatabaseName(), "-f", "/tmp/test-schema.sql");
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("Cannot create the gateway test schema: " + result.getStderr());
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot create the gateway test schema", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while the gateway test schema was created", e);
        }
    }
}
