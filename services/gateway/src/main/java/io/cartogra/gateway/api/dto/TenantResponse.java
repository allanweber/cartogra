package io.cartogra.gateway.api.dto;

import io.cartogra.gateway.domain.Tenant;

import java.time.Instant;
import java.util.UUID;

public record TenantResponse(UUID id, String name, String slug, String plan, Instant createdAt) {
    public static TenantResponse from(Tenant t) {
        return new TenantResponse(t.id(), t.name(), t.slug(), t.plan(), t.createdAt());
    }
}
