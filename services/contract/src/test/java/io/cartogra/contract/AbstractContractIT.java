package io.cartogra.contract;

import io.cartogra.contract.infrastructure.registry.RegistryNameClient;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Base for contract integration tests: one shared Testcontainers Postgres and Kafka, a real HTTP port, and a
 * consumer group of its own so this context never splits the registry topic with another test context. Registry's
 * name lookup is a mock that, by default, knows every requested service as {@code service-<id>}; a test stubs it
 * again to make Registry not know a service or to make it fail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractContractIT {

    @MockitoBean
    protected RegistryNameClient registryNameClient;

    @BeforeEach
    void registryKnowsEveryService() {
        when(registryNameClient.findNames(any(), any())).thenAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(1);
            if (ids == null) {
                return Map.of();
            }
            return ids.stream().collect(Collectors.toMap(id -> id, id -> "service-" + id));
        });
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=contract");
        registry.add("spring.datasource.username", () -> PostgresTestSupport.APP_USER);
        registry.add("spring.datasource.password", () -> PostgresTestSupport.APP_PASSWORD);
        registry.add("spring.flyway.user", () -> PostgresTestSupport.OWNER_USER);
        registry.add("spring.flyway.password", () -> PostgresTestSupport.OWNER_PASSWORD);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "contract-it-" + UUID.randomUUID());
        registry.add("contract.notifications.allow-local-webhooks", () -> "true");
    }
}
