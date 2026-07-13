package io.cartogra.ingestion.api.dto;

import io.cartogra.ingestion.domain.ScmConnection;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record ScmConnectionResponse(
        UUID id,
        UUID tenantId,
        String provider,
        Map<String, Object> config,
        boolean syncScheduler,
        int pollIntervalMinutes,
        @Nullable Instant nextSyncAt,
        @Nullable Instant lastSyncAt,
        @Nullable String lastSyncStatus,
        @Nullable String lastSyncError,
        boolean webhookEnabled,
        Instant createdAt,
        Instant updatedAt
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SECRET_KEYS = Set.of("token", "pat", "webhookSecret");

    public static ScmConnectionResponse from(ScmConnection c) {
        return new ScmConnectionResponse(
                c.id(), c.tenantId(), c.provider(), scrubConfig(c.config()),
                c.syncScheduler(), c.pollIntervalMinutes(),
                c.nextSyncAt(), c.lastSyncAt(), c.lastSyncStatus(), c.lastSyncError(),
                c.webhookEnabled(), c.createdAt(), c.updatedAt());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scrubConfig(String rawConfig) {
        try {
            Map<String, Object> map = new LinkedHashMap<>(MAPPER.readValue(rawConfig, Map.class));
            SECRET_KEYS.forEach(map::remove);
            return map;
        } catch (Exception _) {
            return Map.of();
        }
    }
}
