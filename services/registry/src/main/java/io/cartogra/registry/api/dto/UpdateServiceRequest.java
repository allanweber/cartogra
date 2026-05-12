package io.cartogra.registry.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateServiceRequest(
        @NotBlank @Size(max = 255) String name,
        String description,
        String repositoryUrl,
        String techStack,
        String metadata,
        String healthStatus
) {}
