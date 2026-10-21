package io.cartogra.registry.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public record Team(
        UUID id,
        UUID tenantId,
        String name,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Must stay identical to the {@code regexp_replace} in {@code JdbcTeamRepository.findByTenantAndSlug}. */
    public static String slug(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }
}
