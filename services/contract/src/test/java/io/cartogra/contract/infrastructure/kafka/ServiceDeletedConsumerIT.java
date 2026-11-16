package io.cartogra.contract.infrastructure.kafka;

import io.cartogra.common.audit.AuditEventPort;
import io.cartogra.common.event.EventEnvelope;
import io.cartogra.contract.AbstractContractIT;
import io.cartogra.contract.ContractHttp;
import io.cartogra.contract.domain.ContractService;
import io.cartogra.test.KafkaTestSupport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ServiceDeletedConsumerIT extends AbstractContractIT {

    private static final String TOPIC = ServiceDeletedConsumer.TOPIC;
    private static final String SPEC = """
            openapi: 3.0.3
            info: {title: Cascade, version: '1'}
            paths: {}
            """;

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    KafkaListenerEndpointRegistry listenerRegistry;

    @MockitoSpyBean
    ContractService contractService;

    @MockitoSpyBean
    AuditEventPort auditEventPort;

    ContractHttp http;
    UUID tenant;

    @BeforeEach
    void setUp() {
        ContainerTestUtils.waitForAssignment(listenerRegistry.getListenerContainer("serviceDeletedConsumer"), 1);
        http = new ContractHttp(port);
        tenant = UUID.randomUUID();
    }

    @Test
    void serviceDeletedSoftDeletesContractAndBlocksLaterWrites() {
        UUID serviceId = UUID.randomUUID();
        String id = contractId(serviceId);

        send(serviceId, "service.deleted");

        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(http.get(tenant, id).statusCode()).isEqualTo(404));
        assertThat(liveVersions(id)).isZero();
        assertThat(http.create(tenant, serviceId, SPEC).statusCode()).isEqualTo(404);
        verify(auditEventPort, timeout(5_000)).record(argThat(e -> "deleted".equals(e.action())
                && e.entityId().toString().equals(id)));
    }

    @Test
    void replayedDeleteChangesNothingAndAuditsOnce() {
        UUID serviceId = UUID.randomUUID();
        String id = contractId(serviceId);

        String json = envelope(serviceId, "service.deleted");
        send(serviceId, json);
        send(serviceId, json);

        verify(contractService, timeout(15_000).times(2)).deleteForService(tenant, serviceId);
        verify(auditEventPort, times(1)).record(argThat(e -> "deleted".equals(e.action())
                && e.entityId().toString().equals(id)));
        assertThat(tombstones(serviceId)).isEqualTo(1);
    }

    @Test
    void otherServiceEventsAreIgnored() {
        UUID serviceId = UUID.randomUUID();
        String id = contractId(serviceId);

        send(serviceId, "service.updated");

        verify(contractService, after(3_000).never()).deleteForService(eq(tenant), any());
        assertThat(http.get(tenant, id).statusCode()).isEqualTo(200);
    }

    @Test
    void malformedRecordIsDeadLettered() {
        String key = UUID.randomUUID().toString();
        String malformed = "{\"not\":\"an envelope\"";

        try (var dlq = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestSupport.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "contract-dlq-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            dlq.subscribe(List.of(TOPIC + ".dlq"));
            template().send(new ProducerRecord<>(TOPIC, key, malformed));
            template().flush();

            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> {
                for (ConsumerRecord<String, String> record : dlq.poll(Duration.ofMillis(300))) {
                    if (key.equals(record.key()) && malformed.equals(record.value())) {
                        return true;
                    }
                }
                return false;
            });
        }
        verify(contractService, never()).deleteForService(eq(tenant), any());
    }

    private String contractId(UUID serviceId) {
        String body = http.create(tenant, serviceId, SPEC).body();
        return objectMapper.readTree(body).get("data").get("id").stringValue();
    }

    private String envelope(UUID serviceId, String eventType) {
        return objectMapper.writeValueAsString(EventEnvelope.of(eventType, serviceId, tenant, 1,
                Map.of("id", serviceId, "tenantId", tenant, "name", "ledger")));
    }

    private void send(UUID serviceId, String eventTypeOrJson) {
        String json = eventTypeOrJson.startsWith("{") ? eventTypeOrJson : envelope(serviceId, eventTypeOrJson);
        var template = template();
        template.send(new ProducerRecord<>(TOPIC, serviceId.toString(), json));
        template.flush();
    }

    private KafkaTemplate<String, String> template() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestSupport.KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
    }

    private int liveVersions(String contractId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM contract_versions WHERE contract_id = :id AND deleted_at IS NULL",
                new MapSqlParameterSource("id", UUID.fromString(contractId)), Integer.class);
    }

    private int tombstones(UUID serviceId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM deleted_services WHERE tenant_id = :t AND service_id = :s",
                new MapSqlParameterSource().addValue("t", tenant).addValue("s", serviceId), Integer.class);
    }
}
