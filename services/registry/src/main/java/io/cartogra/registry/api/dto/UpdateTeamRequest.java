package io.cartogra.registry.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateTeamRequest(@NotBlank @Size(max = 255) String name) {}
