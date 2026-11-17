package io.cartogra.registry.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record ServiceNamesRequest(
        @NotNull UUID tenantId,
        @NotEmpty @Size(max = 100) List<@NotNull UUID> serviceIds
) {
}
