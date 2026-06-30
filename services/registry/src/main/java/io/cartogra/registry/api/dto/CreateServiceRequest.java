package io.cartogra.registry.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateServiceRequest(
        @NotBlank @Size(max = 255) String name,
        String description,
        UUID teamId,
        String repositoryUrl,
        List<String> techStack,
        String metadata,
        String healthEndpoint
) {}
