package io.cartogra.contract.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.contract.domain.ContractService;
import io.cartogra.contract.domain.ServiceProjectionService;
import io.cartogra.web.tenant.TenantContext;
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
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.stream.StreamSupport;

/**
 * Keeps the service-name projection from {@code service.registered}/{@code service.updated} and cascades
 * {@code service.deleted} to the service's contract; other event types are acked and ignored.
 */
@Component
public class ServiceEventConsumer {

    static final String TOPIC = "cartogra.registry.service.changed";
    static final String SERVICE_REGISTERED = "service.registered";
    static final String SERVICE_UPDATED = "service.updated";
    static final String SERVICE_DELETED = "service.deleted";

    private static final TextMapGetter<Headers> HEADERS_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers carrier) {
            return () -> StreamSupport.stream(carrier.spliterator(), false).map(Header::key).iterator();
        }

        @Override
        public String get(Headers carrier, String key) {
            if (carrier == null) return null;
            Header h = carrier.lastHeader(key);
            return h != null ? new String(h.value(), StandardCharsets.UTF_8) : null;
        }
    };

    private static final Logger log = LoggerFactory.getLogger(ServiceEventConsumer.class);

    private final ContractService contractService;
    private final ServiceProjectionService serviceProjectionService;
    private final ObjectMapper objectMapper;

    public ServiceEventConsumer(ContractService contractService, ServiceProjectionService serviceProjectionService,
                                ObjectMapper objectMapper) {
        this.contractService = contractService;
        this.serviceProjectionService = serviceProjectionService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(id = "serviceEventConsumer", topics = TOPIC, groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        Context ctx = W3CTraceContextPropagator.getInstance().extract(
                Context.current(), record.headers(), HEADERS_GETTER);

        try (Scope _ = ctx.makeCurrent()) {
            EventEnvelope<JsonNode> envelope = objectMapper.readValue(
                    record.value(), new TypeReference<EventEnvelope<JsonNode>>() {});
            switch (envelope.eventType()) {
                case SERVICE_REGISTERED, SERVICE_UPDATED -> TenantContext.runAs(envelope.tenantId(),
                        () -> serviceProjectionService.recordName(envelope.tenantId(), envelope.entityId(),
                                envelope.payload().required("name").asString()));
                case SERVICE_DELETED -> TenantContext.runAs(envelope.tenantId(),
                        () -> contractService.deleteForService(envelope.tenantId(), envelope.entityId()));
                default -> {
                    ack.acknowledge();
                    return;
                }
            }
            log.info("Applied {} eventId={} serviceId={} tenant={}",
                    envelope.eventType(), envelope.eventId(), envelope.entityId(), envelope.tenantId());
            ack.acknowledge();
        } catch (Exception e) {
            log.warn("Failed to process {} key={} partition={} offset={}: {}",
                    record.topic(), record.key(), record.partition(), record.offset(), e.getMessage(), e);
            throw e;
        }
    }
}
