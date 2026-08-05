package io.cartogra.topology.repository;

import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyType;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record DependencyFilter(
        @Nullable UUID serviceId,
        @Nullable DependencyType type,
        @Nullable DependencyProtocol protocol
) {
    public static DependencyFilter empty() {
        return new DependencyFilter(null, null, null);
    }
}
