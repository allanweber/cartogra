package io.cartogra.contract.domain;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** A service with at least one live API edge into the producer, its edges merged. */
public record Consumer(
        UUID serviceId,
        @Nullable String serviceName,
        List<String> dependencyTypes,
        List<String> protocols
) {
    public Consumer withName(@Nullable String name) {
        return new Consumer(serviceId, name, dependencyTypes, protocols);
    }
}
