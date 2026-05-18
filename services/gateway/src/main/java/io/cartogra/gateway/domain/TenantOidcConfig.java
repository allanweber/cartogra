package io.cartogra.gateway.domain;

import java.time.Instant;
import java.util.UUID;

public record TenantOidcConfig(
    UUID id,
    UUID tenantId,
    String discoveryUri,
    String clientId,
    String clientSecret,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt
) {}
