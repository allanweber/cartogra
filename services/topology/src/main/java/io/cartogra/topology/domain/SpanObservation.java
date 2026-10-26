package io.cartogra.topology.domain;

import org.jspecify.annotations.Nullable;

/**
 * The slice of a span that matters for edge inference. {@code peerName} comes from
 * {@code peer.service}; {@code peerHost} from {@code server.address} (port stripped); {@code dbName}
 * from {@code db.namespace} (or legacy {@code db.name}); {@code destination} from
 * {@code messaging.destination.name}.
 */
public record SpanObservation(
        String serviceName,
        SpanKind kind,
        String traceId,
        String spanId,
        @Nullable String parentSpanId,
        @Nullable String peerName,
        @Nullable String peerHost,
        @Nullable DependencyProtocol protocol,
        @Nullable String dbName,
        @Nullable String destination
) {}
