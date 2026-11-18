package io.cartogra.registry.api.dto;

import io.cartogra.common.validation.NoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

/** The whole team as it should be after saving; {@code serviceIds} and {@code memberIds} are the full sets. */
public record UpdateTeamRequest(
        @NotBlank @Size(min = 1, max = 255) @NoHtml String name,
        @NotNull @Size(max = 1000) Set<UUID> serviceIds,
        @NotNull @Size(max = 1000) Set<UUID> memberIds) {}
