package io.cartogra.topology.domain.event;

import io.cartogra.topology.domain.Dependency;

import java.util.UUID;

/** One edge row of {@code dependencies}; {@code dependencyType} and {@code protocol} use the lowercase db values. */
public record DependencyChangedPayload(
        UUID edgeId,
        UUID sourceServiceId,
        UUID targetServiceId,
        String dependencyType,
        String protocol
) {
    public static DependencyChangedPayload from(Dependency dependency) {
        return new DependencyChangedPayload(dependency.id(), dependency.sourceServiceId(),
                dependency.targetServiceId(), dependency.type().toDbValue(), dependency.protocol().toDbValue());
    }
}
