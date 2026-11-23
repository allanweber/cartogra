package io.cartogra.contract.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.contract.AbstractContractIT;
import io.cartogra.contract.ContractHttp;
import io.cartogra.contract.domain.InboundEdge;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Topology's edge events build the consumer list that {@code GET /{id}/consumers} returns. */
class DependencyEventConsumerIT extends AbstractContractIT {

    private static final String TOPIC = DependencyEventConsumer.TOPIC;
    private static final String SPEC = """
            openapi: 3.0.3
            info: {title: Consumers, version: '1'}
            paths: {}
            """;

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired KafkaListenerEndpointRegistry listenerRegistry;

    ContractHttp http;
    UUID tenant;
    UUID producer;
    String contractId;

    @BeforeEach
    void setUp() {
        ContainerTestUtils.waitForAssignment(listenerRegistry.getListenerContainer("dependencyEventConsumer"), 1);
        http = new ContractHttp(port);
        tenant = UUID.randomUUID();
        producer = UUID.randomUUID();
        contractId = objectMapper.readTree(http.create(tenant, producer, SPEC).body())
                .get("data").get("id").stringValue();
    }

    @Test
    void declaredAndObservedEdgesOfOneServiceMergeIntoOneConsumer() {
        UUID consumer = UUID.randomUUID();
        send("dependency.declared", edge(consumer, producer, "declared", "http"));
        send("dependency.observed", edge(consumer, producer, "observed", "http"));
        send("dependency.observed", edge(consumer, producer, "observed", "grpc"));

        JsonNode item = awaitConsumers(1).get("items").get(0);
        assertThat(item.get("serviceId").stringValue()).isEqualTo(consumer.toString());
        assertThat(item.get("serviceName").stringValue()).isEqualTo("service-" + consumer);
        assertThat(strings(item.get("dependencyTypes"))).containsExactly("declared", "observed");
        assertThat(strings(item.get("protocols"))).containsExactly("grpc", "http");
    }

    @Test
    void removedDropsTheConsumerAndALateReplayCannotBringItBack() {
        InboundEdge edge = edge(UUID.randomUUID(), producer, "declared", "http");
        String declared = envelope("dependency.declared", edge);
        sendRaw(edge, declared);
        awaitConsumers(1);

        send("dependency.removed", edge);
        awaitConsumers(0);

        sendRaw(edge, declared);
        sendMarker();
        assertThat(consumers().get("total").asInt()).isZero();
    }

    @Test
    void aRemovedThatArrivesFirstIsFinal() {
        InboundEdge edge = edge(UUID.randomUUID(), producer, "declared", "http");
        send("dependency.removed", edge);
        send("dependency.declared", edge);
        sendMarker();

        assertThat(consumers().get("total").asInt()).isZero();
    }

    @Test
    void onlyApiProtocolsLiveServicesAndOtherServicesCount() {
        UUID http1 = UUID.randomUUID();
        UUID kafkaOnly = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        send("dependency.declared", edge(http1, producer, "declared", "http"));
        send("dependency.declared", edge(kafkaOnly, producer, "declared", "kafka"));
        send("dependency.declared", edge(UUID.randomUUID(), producer, "declared", "db"));
        send("dependency.declared", edge(producer, producer, "declared", "http"));
        send("dependency.declared", edge(deleted, producer, "declared", "http"));
        send("dependency.declared", edge(UUID.randomUUID(), UUID.randomUUID(), "declared", "http"));
        tombstone(deleted);
        sendMarker();

        JsonNode page = consumers();
        assertThat(page.get("total").asInt()).isEqualTo(1);
        assertThat(page.get("items").get(0).get("serviceId").stringValue()).isEqualTo(http1.toString());
    }

    @Test
    void anotherTenantsEdgesNeverShow() {
        UUID otherTenant = UUID.randomUUID();
        InboundEdge edge = edge(UUID.randomUUID(), producer, "declared", "http");
        sendRaw(edge, objectMapper.writeValueAsString(
                EventEnvelope.of("dependency.declared", edge.edgeId(), otherTenant, 1, edge)));
        sendMarker();

        assertThat(consumers().get("total").asInt()).isZero();
    }

    @Test
    void consumersArePagedAndSortedByName() {
        List<UUID> sources = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID source = UUID.randomUUID();
            sources.add(source);
            send("dependency.declared", edge(source, producer, "declared", "http"));
        }
        awaitConsumers(3);

        JsonNode page = objectMapper.readTree(
                http.read(tenant, "/" + contractId + "/consumers?limit=2&offset=0").body()).get("data");
        assertThat(page.get("total").asInt()).isEqualTo(3);
        assertThat(page.get("limit").asInt()).isEqualTo(2);
        assertThat(page.get("items")).hasSize(2);
        List<String> expected = sources.stream().map(id -> "service-" + id).sorted().limit(2).toList();
        assertThat(page.get("items")).extracting(n -> n.get("serviceName").stringValue())
                .containsExactlyElementsOf(expected);
    }

    @Test
    void anUnknownEventTypeIsAckedAndTheNextEventIsApplied() {
        InboundEdge edge = edge(UUID.randomUUID(), producer, "declared", "http");
        send("dependency.renamed", edge);
        send("dependency.declared", edge);

        awaitConsumers(1);
    }

    @Test
    void aMalformedEventGoesToTheDeadLetterTopic() {
        String key = UUID.randomUUID().toString();
        String malformed = "{\"not\":\"an envelope\"";
        try (var dlq = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestSupport.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "contract-dependency-dlq-it-" + UUID.randomUUID(),
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
    }

    @Test
    void anUnknownContractIs404() {
        HttpResponse<String> resp = http.read(tenant, "/" + UUID.randomUUID() + "/consumers");
        assertThat(resp.statusCode()).isEqualTo(404);
    }

    private JsonNode awaitConsumers(int total) {
        JsonNode[] page = new JsonNode[1];
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    page[0] = consumers();
                    assertThat(page[0].get("total").asInt()).isEqualTo(total);
                });
        return page[0];
    }

    private JsonNode consumers() {
        HttpResponse<String> resp = http.read(tenant, "/" + contractId + "/consumers");
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        return objectMapper.readTree(resp.body()).get("data");
    }

    /**
     * Sends an edge on a key of its own and waits until it is stored. The partition count of the topic is one in
     * the test broker, so every record sent before it has been processed too.
     */
    private void sendMarker() {
        UUID marker = UUID.randomUUID();
        InboundEdge edge = edge(marker, UUID.randomUUID(), "declared", "http");
        send("dependency.declared", edge);
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .until(() -> jdbc.queryForObject(
                        "SELECT count(*) FROM inbound_edges WHERE tenant_id = :t AND edge_id = :e",
                        new MapSqlParameterSource().addValue("t", tenant).addValue("e", edge.edgeId()),
                        Integer.class) == 1);
    }

    private void tombstone(UUID serviceId) {
        jdbc.update("""
                INSERT INTO services (tenant_id, service_id, updated_at, deleted_at) VALUES (:t, :s, now(), now())
                """, new MapSqlParameterSource().addValue("t", tenant).addValue("s", serviceId));
    }

    private static InboundEdge edge(UUID source, UUID target, String type, String protocol) {
        return new InboundEdge(UUID.randomUUID(), source, target, type, protocol);
    }

    private String envelope(String eventType, InboundEdge edge) {
        return objectMapper.writeValueAsString(EventEnvelope.of(eventType, edge.edgeId(), tenant, 1, edge));
    }

    private void send(String eventType, InboundEdge edge) {
        sendRaw(edge, envelope(eventType, edge));
    }

    private void sendRaw(InboundEdge edge, String json) {
        template().send(new ProducerRecord<>(TOPIC, edge.edgeId().toString(), json));
        template().flush();
    }

    private KafkaTemplate<String, String> template() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestSupport.KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.stringValue()));
        return values;
    }
}
