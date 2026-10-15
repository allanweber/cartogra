package io.cartogra.topology.infrastructure.kafka;

import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyType;
import io.cartogra.topology.repository.DependencyRepository;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.GraphNodeUpsert;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "topology.graph-view.refresh-interval=PT1H")
class OtelSpanWorkerIT {

    private static final String TOPIC = "cartogra.observability.spans";
    private static final String DLQ = TOPIC + ".dlq";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=topology");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "otel-span-worker-it-" + UUID.randomUUID());
    }

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @Autowired KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired GraphNodeRepository graphNodeRepository;
    @Autowired DependencyRepository dependencyRepository;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired MeterRegistry meterRegistry;

    private KafkaTemplate<String, String> template;
    private UUID tenant;
    private UUID checkout;
    private UUID payments;

    @BeforeEach
    void setUp() {
        ContainerTestUtils.waitForAssignment(listenerRegistry.getListenerContainer("otelSpanWorker"), 1);
        template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
        tenant = UUID.randomUUID();
        checkout = addNode("checkout");
        payments = addNode("payments");
    }

    @AfterEach
    void tearDown() {
        template.destroy();
    }

    private UUID addNode(String name) {
        UUID serviceId = UUID.randomUUID();
        graphNodeRepository.upsert(new GraphNodeUpsert(tenant, serviceId, name, null, "STANDARD", "HEALTHY"));
        return serviceId;
    }

    private static String newId(int bytes) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, bytes * 2);
    }

    private void send(String fixture, Map<String, String> vars) throws Exception {
        String json = new String(new ClassPathResource("otel/" + fixture).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        for (var e : vars.entrySet()) {
            json = json.replace(e.getKey(), e.getValue());
        }
        template.send(new ProducerRecord<>(TOPIC, json));
        template.flush();
    }

    private Map<String, String> vars(String trace, String clientSpan, String source, String target) {
        return Map.of("__TENANT__", tenant.toString(), "__TRACE__", trace, "__CLIENT_SPAN__", clientSpan,
                "__SERVER_SPAN__", newId(8), "__SOURCE__", source, "__TARGET__", target);
    }

    private boolean edgeExists(UUID source, UUID target) {
        return dependencyRepository.findByEdgeIdentity(tenant, source, target,
                DependencyType.OBSERVED, DependencyProtocol.HTTP).isPresent();
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class);
        return n == null ? 0 : n;
    }

    private void awaitEdge(UUID source, UUID target) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300))
                .until(() -> edgeExists(source, target));
    }

    @Test
    void clientSpanWithPeerServiceCreatesOneObservedEdgeAndReplayIsIdempotent() throws Exception {
        String trace = newId(16);
        send("client-peer-service.json", vars(trace, newId(8), "checkout", "payments"));
        awaitEdge(checkout, payments);

        send("client-peer-service.json", vars(trace, newId(8), "checkout", "payments"));
        send("client-peer-service.json", vars(trace, newId(8), "checkout", "payments"));
        Thread.sleep(2000);

        assertThat(count("dependencies")).isEqualTo(1);
        Long withLastSeen = jdbc.queryForObject(
                "SELECT count(*) FROM dependencies WHERE tenant_id = :t AND last_seen_at IS NOT NULL",
                new MapSqlParameterSource("t", tenant), Long.class);
        assertThat(withLastSeen).isEqualTo(1L);
    }

    @Test
    void ipTargetJoinsWithServerSpanClientFirst() throws Exception {
        String trace = newId(16);
        String clientSpan = newId(8);
        send("client-ip-target.json", vars(trace, clientSpan, "checkout", "payments"));
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> count("pending_span_links") == 1);

        send("server-child.json", vars(trace, clientSpan, "checkout", "payments"));

        awaitEdge(checkout, payments);
        assertThat(count("pending_span_links")).isZero();
        assertThat(count("unresolved_span_services")).isZero();
    }

    @Test
    void ipTargetJoinsWithServerSpanServerFirst() throws Exception {
        String trace = newId(16);
        String clientSpan = newId(8);
        send("server-child.json", vars(trace, clientSpan, "checkout", "payments"));
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> count("pending_span_links") == 1);

        send("client-ip-target.json", vars(trace, clientSpan, "checkout", "payments"));

        awaitEdge(checkout, payments);
        assertThat(count("pending_span_links")).isZero();
    }

    @Test
    void unknownPeerServiceIsCountedAndHostIsNot() throws Exception {
        send("client-peer-service.json", vars(newId(16), newId(8), "checkout", "ghost"));
        send("client-peer-service.json", vars(newId(16), newId(8), "checkout", "ghost"));
        send("client-ip-target.json", vars(newId(16), newId(8), "checkout", "payments"));

        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> count("pending_span_links") == 3);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT name, seen_count FROM unresolved_span_services WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant));
        assertThat(row).containsEntry("name", "ghost");
        assertThat((Long) row.get("seen_count")).isBetween(1L, 2L);
        assertThat(count("unresolved_span_services")).isEqualTo(1);
    }

    @Test
    void messagingSpanAndSelfCallCreateNoEdge() throws Exception {
        send("messaging-span.json", vars(newId(16), newId(8), "checkout", "payments"));
        send("client-peer-service.json", vars(newId(16), newId(8), "checkout", "checkout"));
        send("client-peer-service.json", vars(newId(16), newId(8), "payments", "checkout"));

        awaitEdge(payments, checkout);

        assertThat(edgeExists(checkout, payments)).isFalse();
        assertThat(edgeExists(checkout, checkout)).isFalse();
        assertThat(count("dependencies")).isEqualTo(1);
    }

    @Test
    void batchWithoutTenantIsDeadLetteredWithReasonAndWritesNothing() throws Exception {
        String trace = newId(16);
        double before = meterRegistry.counter("cartogra.spans.quarantined", "reason", "tenant_missing").count();

        send("no-tenant.json", vars(trace, newId(8), "checkout", "payments"));

        try (var consumer = dlqConsumer()) {
            ConsumerRecord<String, String> dead = pollFor(consumer, trace);
            assertThat(dead).isNotNull();
            Header message = dead.headers().lastHeader("kafka_dlt-exception-message");
            assertThat(new String(message.value(), StandardCharsets.UTF_8)).contains("TENANT_MISSING");
        }
        assertThat(count("dependencies")).isZero();
        assertThat(meterRegistry.counter("cartogra.spans.quarantined", "reason", "tenant_missing").count())
                .isGreaterThan(before);
    }

    @Test
    void validResourceIsIngestedEvenWhenAnotherResourceInTheRecordIsQuarantined() throws Exception {
        String trace = newId(16);

        send("mixed-tenants.json", vars(trace, newId(8), "checkout", "payments"));

        awaitEdge(checkout, payments);
        try (var consumer = dlqConsumer()) {
            ConsumerRecord<String, String> dead = pollFor(consumer, trace);
            assertThat(dead).isNotNull();
        }
    }

    @Test
    void unknownTenantIsDeadLettered() throws Exception {
        String trace = newId(16);
        var vars = new java.util.HashMap<>(vars(trace, newId(8), "checkout", "payments"));
        vars.put("__TENANT__", UUID.randomUUID().toString());

        send("client-peer-service.json", vars);

        try (var consumer = dlqConsumer()) {
            ConsumerRecord<String, String> dead = pollFor(consumer, trace);
            assertThat(dead).isNotNull();
            assertThat(new String(dead.headers().lastHeader("kafka_dlt-exception-message").value(),
                    StandardCharsets.UTF_8)).contains("TENANT_UNKNOWN");
        }
    }

    private KafkaConsumer<String, String> dlqConsumer() {
        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "dlq-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(DLQ));
        return consumer;
    }

    private ConsumerRecord<String, String> pollFor(KafkaConsumer<String, String> consumer, String needle) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                if (r.value() != null && r.value().contains(needle)) {
                    return r;
                }
            }
        }
        return null;
    }
}
