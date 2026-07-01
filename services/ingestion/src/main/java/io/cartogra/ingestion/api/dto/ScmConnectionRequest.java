package io.cartogra.ingestion.api.dto;

import io.cartogra.common.validation.NoHtml;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

public record ScmConnectionRequest(
        @Nullable @Size(max = 50) @Pattern(regexp = "(?i)github|azuredevops", message = "provider must be one of: github, azuredevops") @NoHtml String provider,
        @Nullable @Size(max = 10_000) String config,
        @Nullable Boolean syncScheduler,
        @Nullable @Positive @Max(1440) Integer pollIntervalMinutes,
        @Nullable Boolean webhookEnabled
) {}
