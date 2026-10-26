package io.cartogra.topology.infrastructure.otel;

import org.jspecify.annotations.Nullable;

import java.util.List;

/** Minimal view of an OTLP/JSON {@code ExportTraceServiceRequest}; unknown fields are ignored. */
public record OtlpTraceRequest(@Nullable List<ResourceSpans> resourceSpans) {

    public record ResourceSpans(@Nullable Resource resource, @Nullable List<ScopeSpans> scopeSpans) {}

    public record Resource(@Nullable List<KeyValue> attributes) {}

    public record ScopeSpans(@Nullable List<Span> spans) {}

    public record Span(
            @Nullable String traceId,
            @Nullable String spanId,
            @Nullable String parentSpanId,
            @Nullable Object kind,
            @Nullable List<KeyValue> attributes) {}

    public record KeyValue(String key, @Nullable AnyValue value) {}

    public record AnyValue(@Nullable String stringValue, @Nullable Object intValue, @Nullable Boolean boolValue) {

        @Nullable
        public String asString() {
            if (stringValue != null) {
                return stringValue;
            }
            if (boolValue != null) {
                return boolValue.toString();
            }
            return intValue != null ? intValue.toString() : null;
        }
    }
}
