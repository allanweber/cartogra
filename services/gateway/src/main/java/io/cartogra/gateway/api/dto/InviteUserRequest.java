package io.cartogra.gateway.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record InviteUserRequest(
    @NotBlank @Email @Size(max = 255) String email,
    @NotBlank @Pattern(regexp = "VIEWER|MEMBER|TEAM_OWNER|ADMIN") String role,
    @Nullable UUID teamId
) {}
