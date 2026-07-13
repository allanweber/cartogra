package io.cartogra.gateway.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateUserRoleRequest(
    @NotBlank @Pattern(regexp = "VIEWER|MEMBER|TEAM_OWNER|ADMIN") String role
) {}
