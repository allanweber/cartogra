package io.cartogra.ingestion.infrastructure.kafka;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.cartogra.common.event.EventEnvelope;
import io.cartogra.common.event.SyncCommandPayload;
import io.cartogra.ingestion.application.usecase.ExecuteSyncUseCase;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.stream.StreamSupport;

@Component
public class SyncCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(SyncCommandConsumer.class);

    private final ExecuteSyncUseCase executeSyncUseCase;
    private final ObjectMapper objectMapper;

    public SyncCommandConsumer(ExecuteSyncUseCase executeSyncUseCase, ObjectMapper objectMapper) {
        this.executeSyncUseCase = executeSyncUseCase;
        this.objectMapper = objectMapper;
    }

    private static final TextMapGetter<Headers> HEADERS_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers carrier) {
            return () -> StreamSupport.stream(carrier.spliterator(), false)
                    .map(Header::key).iterator();
        }

        @Override
        public String get(Headers carrier, String key) {
            if (carrier == null) return null;
            Header h = carrier.lastHeader(key);
            return h != null ? new String(h.value(), StandardCharsets.UTF_8) : null;
        }
    };

    @KafkaListener(topics = "cartogra.registry.sync.command", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record) {
        Context ctx = W3CTraceContextPropagator.getInstance().extract(
                Context.current(), record.headers(), HEADERS_GETTER);

        try (Scope _ = ctx.makeCurrent()) {
            EventEnvelope<SyncCommandPayload> envelope = objectMapper.readValue(
                    record.value(),
                    new TypeReference<>() {});
            executeSyncUseCase.execute(envelope.payload());
        } catch (Exception ex) {
            log.error("Failed to process sync command key={}: {}", record.key(), ex.getMessage(), ex);
        }
    }
}
