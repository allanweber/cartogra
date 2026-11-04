package io.cartogra.gateway.infrastructure.jwt;

import io.cartogra.common.identity.Actor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JwtClaims(
    UUID userId,
    UUID tenantId,
    String email,
    String name,
    String authProvider,
    List<String> roles,
    Instant exp
) {

    public Actor actor() {
        return new Actor(userId, roles.contains("ADMIN"));
    }
}
