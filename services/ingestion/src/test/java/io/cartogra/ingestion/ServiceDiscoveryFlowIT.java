package io.cartogra.ingestion;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.cartogra.common.event.SyncCommandPayload;
import io.cartogra.ingestion.application.usecase.ExecuteSyncUseCase;
import io.cartogra.test.PostgresTestSupport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {
                "cartogra.registry.sync.command",
                "cartogra.ingestion.sync.completed",
                "cartogra.ingestion.service.discovered",
                "cartogra.ingestion.ownership.resolved"
        }
)
class ServiceDiscoveryFlowIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresTestSupport.POSTGRES.start();
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.flyway.schemas", () -> "ingestion");
        registry.add("spring.flyway.default-schema", () -> "ingestion");
        registry.add("ingestion.workers.k8s.enabled", () -> "false");
    }

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @Autowired
    ExecuteSyncUseCase executeSyncUseCase;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void syncPublishesServiceDiscoveredWithTechStackAndCommit() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();

        String pomContent = java.util.Base64.getEncoder().encodeToString(
                "<project><dependency><artifactId>spring-boot-starter-web</artifactId></dependency></project>"
                        .getBytes());
        String dockerfileContent = java.util.Base64.getEncoder().encodeToString(
                "FROM eclipse-temurin:25-jre-jammy".getBytes());

        wireMock.stubFor(get(urlPathMatching("/orgs/test-org/repos.*"))
                .willReturn(okJson("""
                        [{"id":1,"name":"payments-service","full_name":"test-org/payments-service",
                          "default_branch":"main","archived":false,
                          "html_url":"https://github.com/test-org/payments-service",
                          "description":"Payment processing"}]
                        """)));

        wireMock.stubFor(get(urlEqualTo("/repos/test-org/payments-service/contents/pom.xml"))
                .willReturn(okJson("{\"content\":\"" + pomContent + "\\n\",\"encoding\":\"base64\"}")));

        wireMock.stubFor(get(urlEqualTo("/repos/test-org/payments-service/contents/build.gradle"))
                .willReturn(notFound()));
        wireMock.stubFor(get(urlEqualTo("/repos/test-org/payments-service/contents/build.gradle.kts"))
                .willReturn(notFound()));
        wireMock.stubFor(get(urlEqualTo("/repos/test-org/payments-service/contents/package.json"))
                .willReturn(notFound()));
        wireMock.stubFor(get(urlEqualTo("/repos/test-org/payments-service/contents/go.mod"))
                .willReturn(notFound()));
        wireMock.stubFor(get(urlEqualTo("/repos/test-org/payments-service/contents/Cargo.toml"))
                .willReturn(notFound()));
        wireMock.stubFor(get(urlEqualTo("/repos/test-org/payments-service/contents/requirements.txt"))
                .willReturn(notFound()));
        wireMock.stubFor(get(urlEqualTo("/repos/test-org/payments-service/contents/Dockerfile"))
                .willReturn(okJson("{\"content\":\"" + dockerfileContent + "\\n\",\"encoding\":\"base64\"}")));

        wireMock.stubFor(get(urlPathMatching("/repos/test-org/payments-service/commits.*"))
                .willReturn(okJson("""
                        [{"sha":"abc123","commit":{"committer":{"date":"2026-05-01T00:00:00Z"}}}]
                        """)));

        wireMock.stubFor(get(urlPathMatching("/repos/test-org/payments-service/contents/.*CODEOWNERS.*"))
                .willReturn(notFound()));

        var payload = new SyncCommandPayload(connectionId, tenantId, "github",
                Map.of("token", "test-token", "org", "test-org",
                        "apiBaseUrl", wireMock.baseUrl()));

        executeSyncUseCase.execute(payload);

        List<ConsumerRecord<String, String>> messages = pollTopic(
                "cartogra.ingestion.service.discovered", bootstrapServers);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(messages).isNotEmpty()
        );

        JsonNode envelope = objectMapper.readTree(messages.get(0).value());
        JsonNode p = envelope.get("payload");
        assertThat(objectMapper.treeToValue(p.get("externalId"), String.class))
                .isEqualTo("test-org/payments-service");
        assertThat(objectMapper.treeToValue(p.get("lastCommitSha"), String.class))
                .isEqualTo("abc123");

        JsonNode techStack = p.get("techStack");
        List<String> techStackList = new ArrayList<>();
        techStack.forEach(n -> techStackList.add(objectMapper.treeToValue(n, String.class)));
        assertThat(techStackList).contains("java", "spring-boot");
    }

    private List<ConsumerRecord<String, String>> pollTopic(String topic, String bootstrapServers) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        ))) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
                if (!records.isEmpty()) break;
            }
        }
        return records;
    }
}
