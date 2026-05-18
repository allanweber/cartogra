package io.cartogra.gateway.domain;

import java.time.Instant;
import java.util.UUID;

public record Tenant(
    UUID id,
    UUID tenantId,
    String name,
    String slug,
    String plan,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt
) {}
