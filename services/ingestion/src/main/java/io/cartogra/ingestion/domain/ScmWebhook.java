package io.cartogra.ingestion.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScmWebhook(
        UUID id,
        UUID tenantId,
        UUID scmConnectionId,
        String provider,
        @Nullable String externalId,
        String targetUrl,
        @Nullable String webhookSecret,
        List<String> events,
        String status,
        @Nullable Instant lastReceivedAt,
        @Nullable String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant deletedAt
) {}
