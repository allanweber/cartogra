package io.cartogra.gateway.api.dto;

import jakarta.validation.constraints.Email;

public record UpdateUserInfoRequest(
    String name,
    @Email String email
) {}
