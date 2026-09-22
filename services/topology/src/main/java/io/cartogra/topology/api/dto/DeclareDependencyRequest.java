package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.DependencyProtocol;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record DeclareDependencyRequest(
        @NotNull UUID sourceServiceId,
        @NotNull UUID targetServiceId,
        @NotNull DependencyProtocol protocol,
        @Nullable String metadata
) {
}
