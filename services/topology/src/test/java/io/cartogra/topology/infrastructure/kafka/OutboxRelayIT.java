package io.cartogra.topology.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import io.cartogra.web.outbox.OutboxRelay;
import io.cartogra.web.outbox.OutboxWriter;
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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Outbox rows reach Kafka in id order, keyed by edge id, with the traceparent of the write; the table drains. */
@SpringBootTest(properties = "topology.graph-view.refresh-interval=PT1H")
class OutboxRelayIT {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=topology");
        registry.add("spring.datasource.username", () -> PostgresTestSupport.APP_USER);
        registry.add("spring.datasource.password", () -> PostgresTestSupport.APP_PASSWORD);
        registry.add("spring.flyway.user", () -> PostgresTestSupport.OWNER_USER);
        registry.add("spring.flyway.password", () -> PostgresTestSupport.OWNER_PASSWORD);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "outbox-relay-it-" + UUID.randomUUID());
    }

    @Autowired OutboxWriter outboxWriter;
    @Autowired OutboxRelay outboxRelay;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sendsRowsInOrderWithTheirHeadersAndDrainsTheTable() {
        UUID tenant = UUID.randomUUID();
        UUID edge = UUID.randomUUID();
        Span span = Span.wrap(SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault()));
        try (Scope _ = Context.current().with(span).makeCurrent()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(_ -> {
                outboxWriter.publish(DependencyEventPublisher.TOPIC,
                        EventEnvelope.of(DependencyEventPublisher.DECLARED, edge, tenant, 1, Map.of("n", 1)));
                outboxWriter.publish(DependencyEventPublisher.TOPIC,
                        EventEnvelope.of(DependencyEventPublisher.REMOVED, edge, tenant, 1, Map.of("n", 2)));
            });
        }

        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
            outboxRelay.relayBatch();
            return countFor(tenant) == 0;
        });

        List<ConsumerRecord<String, String>> received = readAll(edge.toString());
        assertThat(received).hasSize(2);
        assertThat(received.get(0).value()).contains("\"eventType\":\"dependency.declared\"");
        assertThat(received.get(1).value()).contains("\"eventType\":\"dependency.removed\"");
        assertThat(new String(received.getFirst().headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8))
                .isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
    }

    private int countFor(UUID tenant) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE tenant_id = ?", Integer.class, tenant);
        return count == null ? 0 : count;
    }

    private List<ConsumerRecord<String, String>> readAll(String key) {
        var props = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestSupport.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-relay-it-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(DependencyEventPublisher.TOPIC));
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
                return found.size() >= 2;
            });
        }
        return found;
    }
}
