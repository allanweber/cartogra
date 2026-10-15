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
        return Optional.of(new SpanObservation(serviceName, kind.get(), span.traceId(), span.spanId(),
                parent, peerName, peerHost, protocol(span.attributes())));
    }

    private static Optional<SpanKind> parseKind(@Nullable Object kind) {
        if (kind == null) {
            return Optional.empty();
        }
        return switch (kind.toString()) {
            case "2", "SPAN_KIND_SERVER" -> Optional.of(SpanKind.SERVER);
            case "3", "SPAN_KIND_CLIENT" -> Optional.of(SpanKind.CLIENT);
            default -> Optional.empty();
        };
    }

    private static @Nullable DependencyProtocol protocol(@Nullable List<KeyValue> attrs) {
        if (attribute(attrs, "rpc.system").filter("grpc"::equalsIgnoreCase).isPresent()) {
            return DependencyProtocol.GRPC;
        }
        if (attribute(attrs, "http.request.method").isPresent() || attribute(attrs, "http.method").isPresent()) {
            return DependencyProtocol.HTTP;
        }
        return null;
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
