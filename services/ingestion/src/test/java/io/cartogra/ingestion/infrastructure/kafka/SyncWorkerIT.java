package io.cartogra.ingestion.infrastructure.kafka;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.cartogra.common.event.EventEnvelope;
import io.cartogra.common.event.SyncCommandPayload;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.awaitility.Awaitility.await;

@SpringBootTest
class SyncWorkerIT {

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
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
    }

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void syncCommand_githubProvider_completesJob() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();

        wireMock.stubFor(get(urlPathMatching("/orgs/acme/repos.*"))
                .willReturn(okJson("""
                        [{"id":1,"name":"api","full_name":"acme/api","default_branch":"main","archived":false}]
                        """)));
        wireMock.stubFor(get(urlPathMatching("/repos/acme/api/contents/.*"))
                .willReturn(notFound()));

        var payload = new SyncCommandPayload(connectionId, tenantId, "github",
                Map.of("token", "test-token", "org", "acme",
                        "apiBaseUrl", wireMock.baseUrl()));
        var envelope = EventEnvelope.of("sync.command", connectionId, tenantId, 1, payload);
        String json = objectMapper.writeValueAsString(envelope);

        var producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        ));
        var template = new KafkaTemplate<>(producerFactory);
        template.send(new ProducerRecord<>("cartogra.registry.sync.command",
                connectionId.toString(), json));
        template.flush();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                wireMock.verify(getRequestedFor(urlPathMatching("/orgs/acme/repos.*")))
        );
    }
}
