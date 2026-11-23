package io.cartogra.web.outbox;

import io.cartogra.common.event.EventEnvelope;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores an event in the outbox in the caller's transaction, so the event exists only if the write that caused it
 * commits. {@link OutboxRelay} sends it later. Outside a transaction it throws: the event would then commit alone.
 */
public class OutboxWriter {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void publish(String topic, EventEnvelope<?> envelope) {
        publishAll(topic, List.of(envelope));
    }

    /** Stores the events in one batch, in order. */
    public void publishAll(String topic, Collection<? extends EventEnvelope<?>> envelopes) {
        if (envelopes.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An outbox event needs an active transaction: "
                    + envelopes.iterator().next().eventType());
        }
        Map<String, String> headers = new HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), headers, Map::put);
        Map<String, String> shared = Map.copyOf(headers);
        repository.insert(envelopes.stream()
                .map(e -> new OutboxEntry(e.tenantId(), topic, e.entityId().toString(),
                        objectMapper.writeValueAsString(e), shared))
                .toList());
    }
}
