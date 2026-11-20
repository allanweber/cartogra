package io.cartogra.contract.infrastructure.kafka;

import io.cartogra.common.audit.AuditEventPort;
import io.cartogra.common.event.EventEnvelope;
import io.cartogra.common.event.UuidV5;
import io.cartogra.common.audit.ActorKind;
import io.cartogra.contract.AbstractContractIT;
import io.cartogra.contract.ContractHttp;
import io.cartogra.contract.domain.SpecDiscoveredPayload;
import io.cartogra.test.KafkaTestSupport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpecDiscoveredConsumerIT extends AbstractContractIT {

    private static final String TOPIC = SpecDiscoveredConsumer.TOPIC;
    private static final Duration WAIT = Duration.ofSeconds(20);

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    KafkaListenerEndpointRegistry listenerRegistry;

    @MockitoSpyBean
    AuditEventPort auditEventPort;

    ContractHttp http;
    UUID tenant;

    @BeforeEach
    void setUp() {
        ContainerTestUtils.waitForAssignment(listenerRegistry.getListenerContainer("specDiscoveredConsumer"), 1);
        ContainerTestUtils.waitForAssignment(listenerRegistry.getListenerContainer("serviceEventConsumer"), 1);
        http = new ContractHttp(port);
        tenant = UUID.randomUUID();
        when(registryNameClient.findServiceIdByExternalId(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void aSpecOfAKnownServiceCreatesTheContractAndVersionOneFromDiscovery() {
        String repo = repo();
        UUID serviceId = registered(repo);

        sendSpec(repo, "openapi.yaml", spec("1.0.0"));

        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(status(repo)).containsExactly("APPLIED"));
        JsonNode latest = latestVersion(serviceId);
        assertThat(latest.get("versionNumber").intValue()).isEqualTo(1);
        assertThat(latest.get("source").stringValue()).isEqualTo("discovery");
        assertThat(latest.get("repositoryFullName").stringValue()).isEqualTo(repo);
        assertThat(latest.get("filePath").stringValue()).isEqualTo("openapi.yaml");
        verify(auditEventPort, timeout(5_000)).record(argThat(e -> "created".equals(e.action())
                && "contract".equals(e.entityType()) && e.actorKind() == ActorKind.SYSTEM
                && serviceId.toString().equals(String.valueOf(e.payload().get("serviceId")))));
    }

    @Test
    void theSameSpecSentAgainChangesNothing() {
        String repo = repo();
        UUID serviceId = registered(repo);
        String json = envelope(repo, "openapi.yaml", spec("1.0.0"));

        send(repo, json);
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(status(repo)).containsExactly("APPLIED"));
        send(repo, json);
        sendSpec(repo(), "openapi.yaml", spec("marker"));

        Awaitility.await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertThat(versionCount(serviceId)).isEqualTo(1));
        assertThat(status(repo)).hasSize(1);
        verify(auditEventPort, times(1)).record(argThat(e -> "contract".equals(e.entityType())
                && serviceId.toString().equals(String.valueOf(e.payload().get("serviceId")))));
    }

    @Test
    void aSpecBeforeItsServiceStaysPendingAndIsAppliedOnServiceRegistered() {
        String repo = repo();
        sendSpec(repo, "openapi.yaml", spec("1.0.0"));
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(status(repo)).containsExactly("PENDING"));

        UUID serviceId = registered(repo);

        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(status(repo)).containsExactly("APPLIED"));
        assertThat(versionCount(serviceId)).isEqualTo(1);
    }

    @Test
    void aServiceWithNoProjectedExternalIdIsResolvedThroughRegistry() {
        String repo = repo();
        UUID serviceId = UUID.randomUUID();
        when(registryNameClient.findServiceIdByExternalId(tenant, repo)).thenReturn(Optional.of(serviceId));

        sendSpec(repo, "openapi.yaml", spec("1.0.0"));

        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(status(repo)).containsExactly("APPLIED"));
        assertThat(versionCount(serviceId)).isEqualTo(1);
        assertThat(projectedExternalId(serviceId)).isEqualTo(repo);
        verify(registryNameClient, atLeastOnce()).findServiceIdByExternalId(eq(tenant), eq(repo));
    }

    @Test
    void aChangedSpecGivesVersionTwoAndACosmeticChangeGivesUnchanged() {
        String repo = repo();
        UUID serviceId = registered(repo);
        sendSpec(repo, "openapi.yaml", spec("1.0.0"));
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(versionCount(serviceId)).isEqualTo(1));

        sendSpec(repo, "openapi.yaml", spec("2.0.0"));
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(versionCount(serviceId)).isEqualTo(2));
        verify(auditEventPort, timeout(5_000)).record(argThat(e -> "version_published".equals(e.action())
                && serviceId.toString().equals(String.valueOf(e.payload().get("serviceId")))));

        sendSpec(repo, "openapi.yaml", "# a comment only\n" + spec("2.0.0"));
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(status(repo)).contains("UNCHANGED"));
        assertThat(versionCount(serviceId)).isEqualTo(2);
    }

    @Test
    void anInvalidSpecIsRejectedWithItsErrorsAndIsNotDeadLettered() {
        String repo = repo();
        UUID serviceId = registered(repo);
        try (var dlq = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestSupport.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "spec-dlq-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            dlq.subscribe(List.of(TOPIC + ".dlq"));

            sendSpec(repo, "openapi.yaml", "swagger: '2.0'\ninfo: {title: old, version: '1'}\n");

            Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(status(repo)).containsExactly("REJECTED"));
            String errors = jdbc.queryForObject(
                    "SELECT errors::text FROM discovered_specs WHERE tenant_id = :t AND repository_full_name = :r",
                    new MapSqlParameterSource().addValue("t", tenant).addValue("r", repo), String.class);
            assertThat(errors).contains("Only OpenAPI 3.x");
            assertThat(versionCount(serviceId)).isZero();
            dlq.poll(Duration.ofSeconds(2)).forEach(r -> assertThat(r.value()).doesNotContain(repo));
        }
    }

    @Test
    void aTombstonedServiceGivesSkippedAndNoContract() {
        String repo = repo();
        UUID serviceId = registered(repo);
        sendService(serviceId, "service.deleted", repo);
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(tombstoned(serviceId)).isTrue());
        when(registryNameClient.findServiceIdByExternalId(tenant, repo)).thenReturn(Optional.of(serviceId));

        sendSpec(repo, "openapi.yaml", spec("1.0.0"));

        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(status(repo)).containsExactly("SKIPPED"));
        assertThat(versionCount(serviceId)).isZero();
    }

    @Test
    void aSpecForADeletedServiceStaysPendingAndIsAppliedToTheNextServiceOfTheRepo() {
        String repo = repo();
        UUID deleted = registered(repo);
        sendService(deleted, "service.deleted", repo);
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(tombstoned(deleted)).isTrue());

        sendSpec(repo, "openapi.yaml", spec("1.0.0"));
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(status(repo)).containsExactly("PENDING"));

        UUID rediscovered = registered(repo);

        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(status(repo)).containsExactly("APPLIED"));
        assertThat(versionCount(rediscovered)).isEqualTo(1);
        assertThat(versionCount(deleted)).isZero();
    }

    @Test
    void anAppliedSpecSentAgainAfterItsServiceIsDeletedIsAppliedToTheNextServiceOfTheRepo() {
        String repo = repo();
        UUID deleted = registered(repo);
        String json = envelope(repo, "openapi.yaml", spec("1.0.0"));
        send(repo, json);
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(status(repo)).containsExactly("APPLIED"));
        sendService(deleted, "service.deleted", repo);
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(tombstoned(deleted)).isTrue());
        UUID next = registered(repo);

        send(repo, json);

        Awaitility.await().atMost(WAIT).untilAsserted(() ->
                assertThat(status(repo)).containsExactly("APPLIED", "APPLIED"));
        JsonNode latest = latestVersion(next);
        assertThat(latest.get("versionNumber").intValue()).isEqualTo(1);
        assertThat(latest.get("source").stringValue()).isEqualTo("discovery");
        assertThat(versionCount(deleted)).isZero();
    }

    @Test
    void aSkippedSpecSentAgainStaysPendingAndIsAppliedToTheNextServiceOfTheRepo() {
        String repo = repo();
        UUID deleted = registered(repo);
        sendService(deleted, "service.deleted", repo);
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(tombstoned(deleted)).isTrue());
        when(registryNameClient.findServiceIdByExternalId(tenant, repo)).thenReturn(Optional.of(deleted));
        String json = envelope(repo, "openapi.yaml", spec("1.0.0"));
        send(repo, json);
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(status(repo)).containsExactly("SKIPPED"));
        when(registryNameClient.findServiceIdByExternalId(tenant, repo)).thenReturn(Optional.empty());

        send(repo, json);
        Awaitility.await().atMost(WAIT).untilAsserted(() ->
                assertThat(status(repo)).containsExactly("SKIPPED", "PENDING"));
        UUID next = registered(repo);

        Awaitility.await().atMost(WAIT).untilAsserted(() ->
                assertThat(status(repo)).containsExactly("SKIPPED", "APPLIED"));
        JsonNode latest = latestVersion(next);
        assertThat(latest.get("versionNumber").intValue()).isEqualTo(1);
        assertThat(latest.get("source").stringValue()).isEqualTo("discovery");
    }

    @Test
    void aRevertToAnOlderSpecPublishesItAgainAsVersionThree() {
        String repo = repo();
        UUID serviceId = registered(repo);
        sendSpec(repo, "openapi.yaml", spec("1.0.0"));
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(versionCount(serviceId)).isEqualTo(1));
        sendSpec(repo, "openapi.yaml", spec("2.0.0"));
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(versionCount(serviceId)).isEqualTo(2));

        sendSpec(repo, "openapi.yaml", spec("1.0.0"));

        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(versionCount(serviceId)).isEqualTo(3));
        JsonNode latest = latestVersion(serviceId);
        assertThat(latest.get("versionNumber").intValue()).isEqualTo(3);
        assertThat(latest.get("label").stringValue()).isEqualTo("1.0.0");
        assertThat(status(repo)).containsExactly("APPLIED", "APPLIED", "APPLIED");
    }

    @Test
    void theSameSpecAfterAManualPublishDoesNotOverwriteTheManualVersion() {
        String repo = repo();
        UUID serviceId = registered(repo);
        sendSpec(repo, "openapi.yaml", spec("1.0.0"));
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(versionCount(serviceId)).isEqualTo(1));
        assertThat(http.publish(tenant, contractId(serviceId), spec("3.0.0")).statusCode()).isEqualTo(201);

        sendSpec(repo, "openapi.yaml", spec("1.0.0"));
        sendSpec(repo(), "openapi.yaml", spec("marker"));

        Awaitility.await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertThat(versionCount(serviceId)).isEqualTo(2));
        JsonNode latest = latestVersion(serviceId);
        assertThat(latest.get("source").stringValue()).isEqualTo("manual");
        assertThat(status(repo)).containsExactly("APPLIED");
    }

    @Test
    void aStalePendingSpecIsNeverAppliedOverANewerSpec() {
        String repo = repo();
        UUID serviceId = UUID.randomUUID();
        sendSpec(repo, "openapi.yaml", spec("1.0.0"));
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(status(repo)).containsExactly("PENDING"));
        when(registryNameClient.findServiceIdByExternalId(tenant, repo)).thenReturn(Optional.of(serviceId));

        sendSpec(repo, "openapi.yaml", spec("2.0.0"));
        Awaitility.await().atMost(WAIT).untilAsserted(() ->
                assertThat(status(repo)).containsExactly("SUPERSEDED", "APPLIED"));

        sendService(serviceId, "service.registered", repo);
        sendSpec(repo(), "openapi.yaml", spec("marker"));

        Awaitility.await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertThat(versionCount(serviceId)).isEqualTo(1));
        assertThat(latestVersion(serviceId).get("label").stringValue()).isEqualTo("2.0.0");
        assertThat(status(repo)).containsExactly("SUPERSEDED", "APPLIED");
    }

    @Test
    void theSamePathInTwoReposGivesTwoContracts() {
        String repoA = repo();
        String repoB = repo();
        UUID serviceA = registered(repoA);
        UUID serviceB = registered(repoB);

        sendSpec(repoA, "openapi.yaml", spec("1.0.0"));
        sendSpec(repoB, "openapi.yaml", spec("1.0.0"));

        Awaitility.await().atMost(WAIT).untilAsserted(() -> {
            assertThat(versionCount(serviceA)).isEqualTo(1);
            assertThat(versionCount(serviceB)).isEqualTo(1);
        });
        assertThat(contractCount()).isEqualTo(2);
    }

    private String repo() {
        return "acme/svc-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String spec(String version) {
        return """
                openapi: 3.0.3
                info: {title: Discovered, version: '%s'}
                paths:
                  /v%s/items:
                    get:
                      responses:
                        '200': {description: ok}
                """.formatted(version, version.replace(".", ""));
    }

    private UUID registered(String repo) {
        UUID serviceId = UUID.randomUUID();
        sendService(serviceId, "service.registered", repo);
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(projectedExternalId(serviceId)).isEqualTo(repo));
        return serviceId;
    }

    private void sendService(UUID serviceId, String eventType, String repo) {
        String json = objectMapper.writeValueAsString(EventEnvelope.of(eventType, serviceId, tenant, 1,
                Map.of("id", serviceId, "tenantId", tenant, "name", "svc-" + serviceId, "externalId", repo)));
        var template = template();
        template.send(new ProducerRecord<>(ServiceEventConsumer.TOPIC, serviceId.toString(), json));
        template.flush();
    }

    private void sendSpec(String repo, String path, String content) {
        send(repo, envelope(repo, path, content));
    }

    private String envelope(String repo, String path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        var payload = new SpecDiscoveredPayload(tenant, UUID.randomUUID(), repo, path, sha256(bytes),
                Base64.getEncoder().encodeToString(bytes));
        UUID entityId = UuidV5.fromNames(tenant.toString(), repo, path);
        return objectMapper.writeValueAsString(EventEnvelope.of("spec.discovered", entityId, tenant, 1, payload));
    }

    private void send(String repo, String json) {
        var template = template();
        template.send(new ProducerRecord<>(TOPIC, repo, json));
        template.flush();
    }

    private List<String> status(String repo) {
        return jdbc.queryForList("""
                SELECT status FROM discovered_specs WHERE tenant_id = :t AND repository_full_name = :r
                ORDER BY created_at
                """, new MapSqlParameterSource().addValue("t", tenant).addValue("r", repo), String.class);
    }

    private int versionCount(UUID serviceId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM contract_versions v JOIN api_contracts c ON c.id = v.contract_id
                WHERE c.tenant_id = :t AND c.service_id = :s AND c.deleted_at IS NULL AND v.deleted_at IS NULL
                """, new MapSqlParameterSource().addValue("t", tenant).addValue("s", serviceId), Integer.class);
        return count == null ? 0 : count;
    }

    private int contractCount() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM api_contracts WHERE tenant_id = :t AND deleted_at IS NULL",
                new MapSqlParameterSource("t", tenant), Integer.class);
        return count == null ? 0 : count;
    }

    private JsonNode latestVersion(UUID serviceId) {
        String body = http.read(tenant, "/" + contractId(serviceId) + "/versions").body();
        return objectMapper.readTree(body).get("data").get("items").get(0);
    }

    private String contractId(UUID serviceId) {
        return jdbc.queryForObject(
                "SELECT id::text FROM api_contracts WHERE tenant_id = :t AND service_id = :s AND deleted_at IS NULL",
                new MapSqlParameterSource().addValue("t", tenant).addValue("s", serviceId), String.class);
    }

    private String projectedExternalId(UUID serviceId) {
        List<String> ids = jdbc.queryForList(
                "SELECT external_id FROM services WHERE tenant_id = :t AND service_id = :s",
                new MapSqlParameterSource().addValue("t", tenant).addValue("s", serviceId), String.class);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private boolean tombstoned(UUID serviceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM services WHERE tenant_id = :t AND service_id = :s AND deleted_at IS NOT NULL)
                """, new MapSqlParameterSource().addValue("t", tenant).addValue("s", serviceId), Boolean.class));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private KafkaTemplate<String, String> template() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestSupport.KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
    }
}
