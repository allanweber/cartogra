package io.cartogra.registry.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateServiceRequest(
        @NotBlank @Size(max = 255) String name,
        String description,
        String repositoryUrl,
        List<String> techStack,
        String metadata,
        String healthStatus,
        String healthEndpoint
) {}
