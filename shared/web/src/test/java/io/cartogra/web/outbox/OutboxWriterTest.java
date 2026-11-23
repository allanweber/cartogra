package io.cartogra.web.outbox;

import io.cartogra.common.event.EventEnvelope;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxWriterTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN_ID = "b7ad6b7169203331";

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void writesTheEnvelopeKeyedByEntityIdWithTheTraceparent() {
        var captured = new AtomicReference<List<OutboxEntry>>();
        var writer = new OutboxWriter(capturing(captured), objectMapper);
        var envelope = EventEnvelope.of("thing.changed", UUID.randomUUID(), UUID.randomUUID(), 1, Map.of("a", "b"));
        TransactionSynchronizationManager.setActualTransactionActive(true);

        Span span = Span.wrap(SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault()));
        try (Scope _ = Context.current().with(span).makeCurrent()) {
            writer.publish("cartogra.test.thing.changed", envelope);
        }

        assertThat(captured.get()).hasSize(1);
        OutboxEntry row = captured.get().getFirst();
        assertThat(row.tenantId()).isEqualTo(envelope.tenantId());
        assertThat(row.topic()).isEqualTo("cartogra.test.thing.changed");
        assertThat(row.key()).isEqualTo(envelope.entityId().toString());
        assertThat(row.payload()).contains("\"eventType\":\"thing.changed\"");
        assertThat(row.headers().get("traceparent")).isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
    }

    @Test
    void writesSeveralEventsInOneBatchInOrder() {
        var captured = new AtomicReference<List<OutboxEntry>>();
        var writer = new OutboxWriter(capturing(captured), objectMapper);
        var first = EventEnvelope.of("thing.changed", UUID.randomUUID(), UUID.randomUUID(), 1, "a");
        var second = EventEnvelope.of("thing.changed", UUID.randomUUID(), UUID.randomUUID(), 1, "b");
        TransactionSynchronizationManager.setActualTransactionActive(true);

        writer.publishAll("cartogra.test.thing.changed", List.of(first, second));

        assertThat(captured.get()).extracting(OutboxEntry::key)
                .containsExactly(first.entityId().toString(), second.entityId().toString());
    }

    @Test
    void anEmptyBatchWritesNothingEvenOutsideATransaction() {
        var captured = new AtomicReference<List<OutboxEntry>>();
        new OutboxWriter(capturing(captured), objectMapper).publishAll("t", List.of());
        assertThat(captured.get()).isNull();
    }

    @Test
    void refusesToWriteOutsideATransaction() {
        var writer = new OutboxWriter(capturing(new AtomicReference<>()), objectMapper);
        var envelope = EventEnvelope.of("thing.changed", UUID.randomUUID(), UUID.randomUUID(), 1, "x");

        assertThatThrownBy(() -> writer.publish("cartogra.test.thing.changed", envelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
    }

    private static OutboxRepository capturing(AtomicReference<List<OutboxEntry>> captured) {
        return new OutboxRepository() {
            @Override
            public void insert(List<OutboxEntry> entries) {
                captured.set(entries);
            }

            @Override
            public List<OutboxMessage> findBatch(int limit) {
                return List.of();
            }

            @Override
            public void delete(long id) {
            }

            @Override
            public void recordFailure(long id, String error) {
            }
        };
    }
}
