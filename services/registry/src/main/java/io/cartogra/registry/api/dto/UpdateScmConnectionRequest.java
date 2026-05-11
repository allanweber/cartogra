package io.cartogra.registry.api.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateScmConnectionRequest(
        @NotBlank String provider,
        @NotBlank String config
) {}
