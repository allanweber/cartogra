package io.cartogra.gateway.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
    @NotBlank @Pattern(regexp = "\\d{6}", message = "token must be exactly 6 digits") @Size(min = 6, max = 6) String token,
    @NotBlank @Size(min = 8, max = 128) String newPassword
) {}
