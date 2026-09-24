package io.cartogra.topology.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/** A declared dependency edge, hydrated with the counterpart service's graph-node projection. */
public record DependencyEdge(
        UUID id,
        GraphNode counterpart,
        DependencyProtocol protocol,
        @Nullable String metadata,
        Instant createdAt,
        Instant updatedAt
) {}
