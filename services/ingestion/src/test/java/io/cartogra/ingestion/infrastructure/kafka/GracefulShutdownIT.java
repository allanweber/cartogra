package io.cartogra.ingestion.infrastructure.kafka;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.cartogra.common.event.EventEnvelope;
import io.cartogra.common.event.SyncCommandPayload;
import io.cartogra.ingestion.IngestionApplication;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Boots the app the same way {@code SpringApplication.run()} does in production (not via
 * {@code @SpringBootTest}'s cached context), so calling {@code context.close()} here faithfully
 * simulates a real SIGTERM-triggered graceful shutdown without interference from Spring's
 * test-context caching/listener machinery.
 */
class GracefulShutdownIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    static {
        PostgresTestSupport.POSTGRES.start();
    }

    private ConfigurableApplicationContext context;

    @AfterEach
    void closeIfStillOpen() {
        if (context != null && context.isActive()) {
            context.close();
        }
    }

    @Test
    void inFlightKafkaMessageFinishesAndCommitsBeforeContextCloses() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();

        wireMock.stubFor(get(urlPathMatching("/orgs/acme/repos.*"))
                .willReturn(okJson("""
                        [{"id":1,"name":"api","full_name":"acme/api","default_branch":"main","archived":false}]
                        """).withFixedDelay(3000)));
        wireMock.stubFor(get(urlPathMatching("/repos/acme/api/contents/.*"))
                .willReturn(notFound()));

        context = new SpringApplicationBuilder(IngestionApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + PostgresTestSupport.POSTGRES.getJdbcUrl()
                                + "&currentSchema=ingestion",
                        "--spring.datasource.username=" + PostgresTestSupport.POSTGRES.getUsername(),
                        "--spring.datasource.password=" + PostgresTestSupport.POSTGRES.getPassword(),
                        "--spring.flyway.schemas=ingestion",
                        "--spring.flyway.default-schema=ingestion",
                        "--spring.kafka.bootstrap-servers=" + KafkaTestSupport.KAFKA.getBootstrapServers());

        ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

        var payload = new SyncCommandPayload(connectionId, tenantId, "github",
                Map.of("token", "test-token", "org", "acme",
                        "apiBaseUrl", wireMock.baseUrl()));
        var envelope = EventEnvelope.of("sync.command", connectionId, tenantId, 1, payload);
        String json = objectMapper.writeValueAsString(envelope);

        var producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestSupport.KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        ));
        var template = new KafkaTemplate<>(producerFactory);
        template.send(new ProducerRecord<>("cartogra.registry.sync.command",
                connectionId.toString(), json));
        template.flush();

        // Wait until the consumer has entered the slow HTTP call - i.e. processing is in flight
        // on the listener container's thread - before triggering shutdown.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                wireMock.verify(getRequestedFor(urlPathMatching("/orgs/acme/repos.*"))));

        Instant closeStart = Instant.now();
        context.close();
        Duration closeDuration = Duration.between(closeStart, Instant.now());

        // The listener container must let the in-flight record finish (including the remaining
        // wiremock delay) before it stops - proving shutdown doesn't abandon in-flight work.
        assertThat(closeDuration).isGreaterThanOrEqualTo(Duration.ofMillis(1500));

        wireMock.verify(1, getRequestedFor(urlPathMatching("/orgs/acme/repos.*")));
    }
}
