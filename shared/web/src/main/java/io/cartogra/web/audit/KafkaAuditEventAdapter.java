package io.cartogra.web.audit;

import io.cartogra.common.audit.AuditEvent;
import io.cartogra.common.audit.AuditEventPort;
import io.cartogra.common.event.EventEnvelope;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * Publishes audit events for registry to persist (ADR-0017). Sends after the surrounding transaction commits,
 * so a rolled-back mutation is never audited, on a virtual thread, so a slow or down broker never blocks or fails
 * the mutation: a failed send is logged and that row is lost (at-least-once only once the broker accepts it).
 */
public class KafkaAuditEventAdapter implements AuditEventPort {

    public static final String TOPIC = "cartogra.platform.audit.recorded";
    public static final String EVENT_TYPE = "audit.recorded";

    private static final Logger log = LoggerFactory.getLogger(KafkaAuditEventAdapter.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaAuditEventAdapter(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(AuditEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            Context context = Context.current();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendAsync(event, context);
                }
            });
        } else {
            sendAsync(event, Context.current());
        }
    }

    private void sendAsync(AuditEvent event, Context context) {
        Thread.ofVirtual().name("audit-publish").start(() -> send(event, context));
    }

    private void send(AuditEvent event, Context context) {
        try {
            var envelope = EventEnvelope.of(EVENT_TYPE, event.entityId(), event.tenantId(), 1, event);
            var record = new ProducerRecord<>(TOPIC, event.entityId().toString(),
                    objectMapper.writeValueAsString(envelope));
            W3CTraceContextPropagator.getInstance().inject(context, record.headers(),
                    (h, k, v) -> h.add(k, v.getBytes(StandardCharsets.UTF_8)));
            kafkaTemplate.send(record).whenComplete((_, ex) -> {
                if (ex != null) {
                    log.warn("audit.recorded publish failed event={} entity={}/{}: {}",
                            event.eventId(), event.entityType(), event.entityId(), ex.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("audit.recorded publish failed event={} entity={}/{}: {}",
                    event.eventId(), event.entityType(), event.entityId(), e.getMessage());
        }
    }
}
