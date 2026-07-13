package io.cartogra.gateway.domain;

import java.time.Instant;
import java.util.UUID;

public record Invitation(
    UUID id,
    UUID tenantId,
    String email,
    String role,
    UUID invitedBy,
    UUID teamId,
    String token,
    Instant tokenExp,
    String status,
    Instant createdAt,
    Instant updatedAt
) {}
