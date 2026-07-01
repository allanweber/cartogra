package io.cartogra.registry.api.dto;

import io.cartogra.common.validation.NoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateServiceRequest(
        @NotBlank @Size(min = 1, max = 255) @NoHtml String name,
        @Size(max = 1_000) @NoHtml String description,
        UUID teamId,
        @Size(max = 2_048) @NoHtml String repositoryUrl,
        @Size(max = 30) List<@Size(min = 1, max = 100) @NoHtml String> techStack,
        @Size(max = 10_000) String metadata,
        @Size(max = 2_048) String healthEndpoint
) {}
