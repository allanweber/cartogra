package io.cartogra.ingestion.api.dto;

import jakarta.validation.constraints.Positive;
import org.jspecify.annotations.Nullable;

/**
 * Create/update payload for an SCM connection. {@code provider} and {@code config}
 * are required on create (enforced in the service); on update, null fields are left unchanged.
 */
public record ScmConnectionRequest(
        @Nullable String provider,
        @Nullable String config,
        @Nullable Boolean syncScheduler,
        @Nullable @Positive Integer pollIntervalMinutes,
        @Nullable Boolean webhookEnabled
) {}
