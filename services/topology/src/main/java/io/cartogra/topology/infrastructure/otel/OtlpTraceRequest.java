package io.cartogra.topology.infrastructure.otel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** Minimal view of an OTLP/JSON {@code ExportTraceServiceRequest}; unknown fields are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OtlpTraceRequest(@Nullable List<ResourceSpans> resourceSpans) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResourceSpans(@Nullable Resource resource, @Nullable List<ScopeSpans> scopeSpans) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Resource(@Nullable List<KeyValue> attributes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScopeSpans(@Nullable List<Span> spans) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Span(
            @Nullable String traceId,
            @Nullable String spanId,
            @Nullable String parentSpanId,
            @Nullable Object kind,
            @Nullable List<KeyValue> attributes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeyValue(String key, @Nullable AnyValue value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnyValue(@Nullable String stringValue, @Nullable Object intValue) {

        @Nullable
        public String asString() {
            if (stringValue != null) {
                return stringValue;
            }
            return intValue != null ? intValue.toString() : null;
        }
    }
}
