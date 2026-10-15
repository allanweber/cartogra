package io.cartogra.topology.repository;

import io.cartogra.topology.domain.SpanKind;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record PendingSpanLink(
        UUID id,
        UUID tenantId,
        String traceId,
        String spanId,
        @Nullable String parentSpanId,
        SpanKind kind,
        String serviceName,
        @Nullable String protocol,
        Instant expiresAt
) {}
