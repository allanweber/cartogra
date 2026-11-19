package io.cartogra.registry.infrastructure.kafka;

import io.cartogra.registry.repository.ServiceDiscoveryCommand;
import io.cartogra.registry.domain.ServiceService;
import io.cartogra.registry.domain.event.ServiceDiscoveredPayload;
import io.cartogra.registry.domain.event.ServiceRemovedPayload;
import io.cartogra.registry.domain.event.ServiceResyncedPayload;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.stream.StreamSupport;

@Component
public class RegistryServiceDiscoveryConsumer {

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

    private static final String SERVICE_DISCOVERED = "service.discovered";
    private static final String SERVICE_REMOVED = "service.removed";
    private static final String SERVICE_RESYNCED = "service.resynced";

    private static final Logger log = LoggerFactory.getLogger(RegistryServiceDiscoveryConsumer.class);

    private final ServiceService serviceService;
    private final ObjectMapper objectMapper;

    public RegistryServiceDiscoveryConsumer(ServiceService serviceService,
                                             ObjectMapper objectMapper) {
        this.serviceService = serviceService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "cartogra.ingestion.service.changed",
            groupId = "registry-discovery-consumer"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.debug("Received ingestion service event key={} partition={} offset={}",
                record.key(), record.partition(), record.offset());

        Context ctx = W3CTraceContextPropagator.getInstance().extract(
                Context.current(), record.headers(), HEADERS_GETTER);

        try (Scope _ = ctx.makeCurrent()) {
            JsonNode envelope = objectMapper.readTree(record.value());
            String eventType = envelope.path("eventType").asString();
            JsonNode payload = envelope.path("payload");
            switch (eventType) {
                case SERVICE_DISCOVERED -> upsertDiscovered(
                        objectMapper.treeToValue(payload, ServiceDiscoveredPayload.class));
                case SERVICE_REMOVED -> {
                    var removed = objectMapper.treeToValue(payload, ServiceRemovedPayload.class);
                    serviceService.removeFromCluster(removed);
                    log.info("Processed service.removed cluster={} ns={} name={} tenant={}",
                            removed.k8sCluster(), removed.k8sNamespace(), removed.name(), removed.tenantId());
                }
                case SERVICE_RESYNCED -> serviceService.reconcileCluster(
                        objectMapper.treeToValue(payload, ServiceResyncedPayload.class));
                default -> log.warn("Ignoring unknown ingestion service event type={} key={}", eventType, record.key());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.warn("Failed to process ingestion service event key={} partition={} offset={}: {}",
                    record.key(), record.partition(), record.offset(), e.getMessage(), e);
            throw e;
        }
    }

    private void upsertDiscovered(ServiceDiscoveredPayload payload) {
        var command = new ServiceDiscoveryCommand(
                payload.tenantId(),
                payload.connectionId(),
                payload.source(),
                payload.externalId(),
                payload.name(),
                payload.description(),
                payload.repositoryUrl(),
                payload.repositoryRef(),
                payload.k8sCluster(),
                payload.k8sNamespace(),
                payload.k8sDeployment(),
                payload.techStack(),
                payload.healthStatus(),
                payload.healthEndpoint(),
                payload.lastCommitAt(),
                payload.lastCommitSha()
        );
        serviceService.upsertDiscovered(command);
        log.info("Upserted discovered service externalId={} source={} health={} tenant={}",
                payload.externalId(), payload.source(), payload.healthStatus(), payload.tenantId());
    }
}
