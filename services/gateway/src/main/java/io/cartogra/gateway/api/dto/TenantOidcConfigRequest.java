package io.cartogra.gateway.api.dto;

import jakarta.validation.constraints.NotBlank;

public record TenantOidcConfigRequest(
    @NotBlank String discoveryUri,
    @NotBlank String clientId,
    @NotBlank String clientSecret
) {}
