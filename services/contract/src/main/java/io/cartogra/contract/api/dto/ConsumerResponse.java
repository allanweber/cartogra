package io.cartogra.contract.api.dto;

import io.cartogra.contract.domain.Consumer;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public record ConsumerResponse(
        UUID serviceId,
        @Nullable String serviceName,
        List<String> dependencyTypes,
        List<String> protocols
) {
    public static ConsumerResponse from(Consumer c) {
        return new ConsumerResponse(c.serviceId(), c.serviceName(), c.dependencyTypes(), c.protocols());
    }
}
