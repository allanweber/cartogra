package io.cartogra.gateway.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptInviteRequest(
    @NotBlank String token,
    @NotBlank @Size(min = 8, max = 128) String password
) {}
