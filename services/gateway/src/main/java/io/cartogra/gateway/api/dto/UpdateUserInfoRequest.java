package io.cartogra.gateway.api.dto;

import io.cartogra.common.validation.NoHtml;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateUserInfoRequest(
    @Size(max = 255) @NoHtml String name,
    @Email @Size(max = 255) String email
) {}
