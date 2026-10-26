package io.cartogra.topology.infrastructure.kafka;

import io.cartogra.topology.config.SpanLinkConfig;
import io.cartogra.topology.domain.ObservedDependencyService;
import io.cartogra.topology.domain.SpanObservation;
import io.cartogra.topology.domain.exception.UnattributedSpanBatchException;
import io.cartogra.topology.infrastructure.otel.OtlpSpanParser;
import io.cartogra.topology.infrastructure.otel.OtlpSpanParser.TenantSpans;
import io.cartogra.topology.infrastructure.otel.OtlpTraceRequest;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns collector-exported OTLP/JSON span batches into {@code observed} dependency edges.
 * The topic carries raw OTLP, not an {@code EventEnvelope} (ADR-0029). Tenant attribution
 * is validated per resource: attributable resources are ingested, and if any resource was not
 * the record then ends on {@code <topic>.dlq} via a non-retryable exception, so quarantined
 * spans stay visible without discarding the valid ones batched with them. A tenant's resources are
 * ingested as one batch so the CLIENT and SERVER halves of a call join in memory whatever their order.
 */
@Component
public class OtelSpanWorker {

    private static final Logger log = LoggerFactory.getLogger(OtelSpanWorker.class);

    private final ObservedDependencyService observedDependencyService;
    private final OtlpSpanParser spanParser;
    private final ObjectMapper objectMapper;

    public OtelSpanWorker(ObservedDependencyService observedDependencyService, OtlpSpanParser spanParser,
                          ObjectMapper objectMapper) {
        this.observedDependencyService = observedDependencyService;
        this.spanParser = spanParser;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            id = "otelSpanWorker",
            topics = SpanLinkConfig.SPANS_TOPIC,
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        Context ctx = W3CTraceContextPropagator.getInstance().extract(
                Context.current(), record.headers(), GraphNodeEventConsumer.HEADERS_GETTER);

        try (Scope _ = ctx.makeCurrent()) {
            OtlpTraceRequest request = objectMapper.readValue(record.value(), OtlpTraceRequest.class);
            List<TenantSpans> groups = spanParser.map(request);

            UnattributedSpanBatchException firstQuarantined = null;
            Map<UUID, List<SpanObservation>> byTenant = new LinkedHashMap<>();
            for (TenantSpans group : groups) {
                try {
                    UUID tenantId = observedDependencyService.requireAttributedTenant(group.tenantRaw());
                    byTenant.computeIfAbsent(tenantId, _ -> new ArrayList<>()).addAll(group.observations());
                } catch (UnattributedSpanBatchException e) {
                    if (firstQuarantined == null) {
                        firstQuarantined = e;
                    }
                }
            }
            byTenant.forEach(observedDependencyService::ingest);
            if (firstQuarantined != null) {
                throw firstQuarantined;
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.warn("Failed to process span batch partition={} offset={}: {}",
                    record.partition(), record.offset(), e.getMessage());
            throw e;
        }
    }
}
