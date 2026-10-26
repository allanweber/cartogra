package io.cartogra.topology.infrastructure.otel;

import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.SpanKind;
import io.cartogra.topology.domain.SpanObservation;
import io.cartogra.topology.infrastructure.otel.OtlpTraceRequest.KeyValue;
import io.cartogra.topology.infrastructure.otel.OtlpTraceRequest.ResourceSpans;
import io.cartogra.topology.infrastructure.otel.OtlpTraceRequest.ScopeSpans;
import io.cartogra.topology.infrastructure.otel.OtlpTraceRequest.Span;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class OtlpSpanMapper {

    public static final String TENANT_ATTRIBUTE = "cartogra.tenant.id";

    /** Observations from one OTLP resource, still carrying the unvalidated tenant attribute. */
    public record TenantSpans(@Nullable String tenantRaw, List<SpanObservation> observations) {}

    public List<TenantSpans> map(OtlpTraceRequest request) {
        List<TenantSpans> result = new ArrayList<>();
        if (request.resourceSpans() == null) {
            return result;
        }
        for (ResourceSpans resourceSpans : request.resourceSpans()) {
            List<KeyValue> resourceAttrs = resourceSpans.resource() != null
                    ? resourceSpans.resource().attributes() : null;
            String tenantRaw = attribute(resourceAttrs, TENANT_ATTRIBUTE).orElse(null);
            Optional<String> serviceName = attribute(resourceAttrs, "service.name").filter(s -> !s.isBlank());
            List<SpanObservation> observations = new ArrayList<>();
            if (serviceName.isPresent() && resourceSpans.scopeSpans() != null) {
                for (ScopeSpans scope : resourceSpans.scopeSpans()) {
                    if (scope.spans() == null) {
                        continue;
                    }
                    for (Span span : scope.spans()) {
                        toObservation(serviceName.get(), span).ifPresent(observations::add);
                    }
                }
            }
            result.add(new TenantSpans(tenantRaw, observations));
        }
        return result;
    }

    private Optional<SpanObservation> toObservation(String serviceName, Span span) {
        Optional<SpanKind> kind = parseKind(span.kind());
        if (kind.isEmpty() || isBlank(span.traceId()) || isBlank(span.spanId())) {
            return Optional.empty();
        }
        String peerName = attribute(span.attributes(), "peer.service").filter(s -> !s.isBlank()).orElse(null);
        String peerHost = attribute(span.attributes(), "server.address")
                .map(OtlpSpanMapper::stripPort).filter(s -> !s.isBlank()).orElse(null);
        String parent = isBlank(span.parentSpanId()) ? null : span.parentSpanId();
        String dbName = attribute(span.attributes(), "db.namespace")
                .or(() -> attribute(span.attributes(), "db.name")).filter(s -> !s.isBlank()).orElse(null);
        String destination = attribute(span.attributes(), "messaging.destination.name")
                .filter(s -> !s.isBlank()).orElse(null);
        return Optional.of(new SpanObservation(serviceName, kind.get(), span.traceId(), span.spanId(),
                parent, peerName, peerHost, protocol(kind.get(), span.attributes(), destination), dbName,
                destination));
    }

    private static Optional<SpanKind> parseKind(@Nullable Object kind) {
        if (kind == null) {
            return Optional.empty();
        }
        return switch (kind.toString()) {
            case "2", "SPAN_KIND_SERVER" -> Optional.of(SpanKind.SERVER);
            case "3", "SPAN_KIND_CLIENT" -> Optional.of(SpanKind.CLIENT);
            case "4", "SPAN_KIND_PRODUCER" -> Optional.of(SpanKind.PRODUCER);
            case "5", "SPAN_KIND_CONSUMER" -> Optional.of(SpanKind.CONSUMER);
            default -> Optional.empty();
        };
    }

    private static @Nullable DependencyProtocol protocol(SpanKind kind, @Nullable List<KeyValue> attrs,
                                                         @Nullable String destination) {
        if (kind == SpanKind.PRODUCER || kind == SpanKind.CONSUMER) {
            return isKafkaDestination(attrs, destination) ? DependencyProtocol.KAFKA : null;
        }
        if (kind == SpanKind.CLIENT && attribute(attrs, "db.system").filter(s -> !s.isBlank()).isPresent()) {
            return DependencyProtocol.DB;
        }
        if (attribute(attrs, "rpc.system").filter("grpc"::equalsIgnoreCase).isPresent()) {
            return DependencyProtocol.GRPC;
        }
        if (attribute(attrs, "http.request.method").isPresent() || attribute(attrs, "http.method").isPresent()) {
            return DependencyProtocol.HTTP;
        }
        return null;
    }

    private static boolean isKafkaDestination(@Nullable List<KeyValue> attrs, @Nullable String destination) {
        return destination != null
                && attribute(attrs, "messaging.system").filter("kafka"::equalsIgnoreCase).isPresent()
                && attribute(attrs, "messaging.destination.temporary").filter("true"::equalsIgnoreCase).isEmpty();
    }

    static String stripPort(String address) {
        String value = address.trim();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 0 ? value.substring(1, end) : value;
        }
        int firstColon = value.indexOf(':');
        if (firstColon >= 0 && firstColon == value.lastIndexOf(':')) {
            return value.substring(0, firstColon);
        }
        return value;
    }

    private static Optional<String> attribute(@Nullable List<KeyValue> attrs, String key) {
        if (attrs == null) {
            return Optional.empty();
        }
        return attrs.stream()
                .filter(kv -> key.equals(kv.key()) && kv.value() != null)
                .map(kv -> kv.value().asString())
                .filter(v -> v != null)
                .findFirst();
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
