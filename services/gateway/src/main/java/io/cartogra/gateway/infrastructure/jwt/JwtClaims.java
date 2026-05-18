package io.cartogra.gateway.infrastructure.jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JwtClaims(
    UUID userId,
    UUID tenantId,
    String email,
    List<String> roles,
    Instant exp
) {}
