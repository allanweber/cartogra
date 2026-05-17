package io.cartogra.gateway.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record LoginRequest(
    UUID tenantId,
    @NotBlank @Email String email,
    @NotBlank String password
) {}
