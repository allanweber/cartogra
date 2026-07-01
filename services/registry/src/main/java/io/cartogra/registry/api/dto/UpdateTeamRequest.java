package io.cartogra.registry.api.dto;

import io.cartogra.common.validation.NoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateTeamRequest(@NotBlank @Size(min = 1, max = 255) @NoHtml String name) {}
