package io.cartogra.ingestion;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.cartogra.common.event.SyncCommandPayload;
import io.cartogra.common.event.UuidV5;
import io.cartogra.ingestion.domain.ScmConnection;
import io.cartogra.ingestion.domain.SyncExecutionService;
import io.cartogra.ingestion.repository.ScmConnectionRepository;
import io.cartogra.ingestion.repository.SpecPathRepository;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SpecDiscoveryFlowIT {

    private static final String TOPIC = "cartogra.ingestion.spec.discovered";
    private static final String CUSTOM_SPEC = "openapi: 3.0.3\ninfo: {title: orders, version: 1.0.0}\npaths: {}\n";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", () -> PostgresTestSupport.APP_USER);
        registry.add("spring.datasource.password", () -> PostgresTestSupport.APP_PASSWORD);
        registry.add("spring.flyway.user", () -> PostgresTestSupport.OWNER_USER);
        registry.add("spring.flyway.password", () -> PostgresTestSupport.OWNER_PASSWORD);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
    }

    @Autowired
    SyncExecutionService syncExecutionService;

    @Autowired
    ScmConnectionRepository scmConnectionRepository;

    @Autowired
    SpecPathRepository specPathRepository;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void syncPublishesOneSpecEventForTheCustomPathAndNoneForARepoWithoutASpec() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        String org = "spec-org-" + UUID.randomUUID().toString().substring(0, 8);
        specPathRepository.saveCustomPaths(tenantId, List.of("svc/openapi.yaml"));

        wireMock.stubFor(get(urlPathMatching("/orgs/" + org + "/repos.*")).willReturn(okJson("""
                [{"id":1,"name":"orders","full_name":"%1$s/orders","default_branch":"main","archived":false},
                 {"id":2,"name":"nospec","full_name":"%1$s/nospec","default_branch":"main","archived":false}]
                """.formatted(org))));
        wireMock.stubFor(get(urlPathMatching("/repos/" + org + "/orders/contents/svc(%2F|/)openapi\\.yaml"))
                .willReturn(okJson(fileJson(CUSTOM_SPEC))));
        wireMock.stubFor(get(urlPathMatching("/repos/" + org + "/orders/contents/openapi\\.yaml"))
                .willReturn(okJson(fileJson("openapi: 3.0.3\ninfo: {title: default, version: 1.0.0}\n"))));
        wireMock.stubFor(get(urlPathMatching("/repos/" + org + "/.*/commits.*")).willReturn(okJson("[]")));
        wireMock.stubFor(get(urlPathMatching("/repos/" + org + "/.*/contents/.*")).atPriority(10)
                .willReturn(notFound()));

        String config = objectMapper.writeValueAsString(Map.of(
                "token", "test-token", "org", org, "apiBaseUrl", wireMock.baseUrl()));
        Instant now = Instant.now();
        scmConnectionRepository.save(new ScmConnection(
                connectionId, tenantId, "github", config,
                false, 15, null, null, null, null, false,
                now, now, null));

        String traceId = UUID.randomUUID().toString().replace("-", "");
        Span parent = Span.wrap(SpanContext.create(traceId, "00f067aa0ba902b7",
                TraceFlags.getSampled(), TraceState.getDefault()));
        try (Scope _ = parent.storeInContext(Context.root()).makeCurrent()) {
            syncExecutionService.execute(new SyncCommandPayload(connectionId, tenantId, "github"));
        }

        List<ConsumerRecord<String, String>> records = pollTopic(tenantId);
        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> record = records.getFirst();
        UUID entityId = UuidV5.fromNames(tenantId.toString(), org + "/orders", "svc/openapi.yaml");
        assertThat(record.key()).isEqualTo(entityId.toString());
        assertThat(new String(record.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8))
                .contains(traceId);

        JsonNode envelope = objectMapper.readTree(record.value());
        assertThat(envelope.get("eventType").stringValue()).isEqualTo("spec.discovered");
        assertThat(envelope.get("entityId").stringValue()).isEqualTo(entityId.toString());
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("tenantId").stringValue()).isEqualTo(tenantId.toString());
        assertThat(payload.get("connectionId").stringValue()).isEqualTo(connectionId.toString());
        assertThat(payload.get("repositoryFullName").stringValue()).isEqualTo(org + "/orders");
        assertThat(payload.get("filePath").stringValue()).isEqualTo("svc/openapi.yaml");
        byte[] bytes = CUSTOM_SPEC.getBytes(StandardCharsets.UTF_8);
        assertThat(payload.get("contentSha256").stringValue())
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThat(new String(Base64.getDecoder().decode(payload.get("contentB64").stringValue()),
                StandardCharsets.UTF_8)).isEqualTo(CUSTOM_SPEC);
        assertThat(payload.has("serviceId")).isFalse();
    }

    private static String fileJson(String content) {
        return "{\"content\":\"" + Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))
                + "\\n\",\"encoding\":\"base64\"}";
    }

    private List<ConsumerRecord<String, String>> pollTopic(UUID tenantId) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestSupport.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "spec-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (r.value().contains(tenantId.toString())) {
                        records.add(r);
                    }
                });
                if (!records.isEmpty()) {
                    consumer.poll(Duration.ofSeconds(1)).forEach(r -> {
                        if (r.value().contains(tenantId.toString())) {
                            records.add(r);
                        }
                    });
                    break;
                }
            }
        }
        return records;
    }
}
