package io.cartogra.registry.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.registry.repository.ServiceDiscoveryCommand;
import io.cartogra.registry.domain.ServiceService;
import io.cartogra.registry.domain.event.ServiceDiscoveredPayload;
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

    private static final Logger log = LoggerFactory.getLogger(RegistryServiceDiscoveryConsumer.class);

    private final ServiceService serviceService;
    private final ObjectMapper objectMapper;

    public RegistryServiceDiscoveryConsumer(ServiceService serviceService,
                                             ObjectMapper objectMapper) {
        this.serviceService = serviceService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "cartogra.ingestion.service.discovered",
            groupId = "${spring.kafka.consumer.group-id:registry-discovery-consumer}"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.debug("Received service.discovered key={} partition={} offset={}",
                record.key(), record.partition(), record.offset());

        Context ctx = W3CTraceContextPropagator.getInstance().extract(
                Context.current(), record.headers(), HEADERS_GETTER);

        try (Scope _ = ctx.makeCurrent()) {
            EventEnvelope<ServiceDiscoveredPayload> envelope = objectMapper.readValue(
                    record.value(),
                    new TypeReference<EventEnvelope<ServiceDiscoveredPayload>>() {});
            ServiceDiscoveredPayload payload = envelope.payload();
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
            ack.acknowledge();
        } catch (Exception e) {
            log.warn("Failed to process service.discovered event key={} partition={} offset={}: {}",
                    record.key(), record.partition(), record.offset(), e.getMessage(), e);
            throw e;
        }
    }
}
