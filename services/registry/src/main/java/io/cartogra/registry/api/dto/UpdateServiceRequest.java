package io.cartogra.registry.api.dto;

import io.cartogra.registry.domain.ServiceTier;
import io.cartogra.common.validation.NoHtml;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record UpdateServiceRequest(
        @NotBlank @Size(min = 1, max = 255) @NoHtml String name,
        @Size(max = 1_000) @NoHtml String description,
        @Size(max = 2_048) @NoHtml String repositoryUrl,
        @Size(max = 30) List<@Size(min = 1, max = 100) @NoHtml String> techStack,
        @Size(max = 10_000) String metadata,
        @Pattern(regexp = "(?i)healthy|degraded|unhealthy|probe_auth_failed|unknown",
                 message = "must be one of: healthy, degraded, unhealthy, probe_auth_failed, unknown")
        String healthStatus,
        @Size(max = 2_048) String healthEndpoint,
        ServiceTier tier,
        @Size(max = 20) List<@Size(min = 1, max = 50)
                             @Pattern(regexp = "[a-zA-Z0-9._:-]+",
                                      message = "tags may only contain letters, digits, dots, underscores, hyphens, and colons")
                             String> tags,
        @DecimalMin(value = "0.0", message = "slaTarget must be between 0.0 and 100.0")
        @DecimalMax(value = "100.0", message = "slaTarget must be between 0.0 and 100.0")
        BigDecimal slaTarget,
        @Size(max = 2_048) @NoHtml String documentationUrl,
        @Size(max = 2_048) @NoHtml String runbookUrl
) {}
