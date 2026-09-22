package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.Dependency;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyType;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record DependencyResponse(
        UUID id,
        UUID sourceServiceId,
        UUID targetServiceId,
        DependencyType type,
        DependencyProtocol protocol,
        @Nullable String metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public static DependencyResponse from(Dependency dependency) {
        return new DependencyResponse(
                dependency.id(),
                dependency.sourceServiceId(),
                dependency.targetServiceId(),
                dependency.type(),
                dependency.protocol(),
                dependency.metadata(),
                dependency.createdAt(),
                dependency.updatedAt()
        );
    }
}
