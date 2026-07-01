package io.cartogra.gateway.api.dto;

import io.cartogra.common.validation.NoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TenantOidcConfigRequest(
    @NotBlank @Size(max = 2_048) @NoHtml String discoveryUri,
    @NotBlank @Size(max = 255) @NoHtml String clientId,
    @NotBlank @Size(max = 512) @NoHtml String clientSecret
) {}
