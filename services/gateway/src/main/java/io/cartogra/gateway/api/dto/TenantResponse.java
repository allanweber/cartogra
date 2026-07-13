package io.cartogra.gateway.api.dto;

import io.cartogra.gateway.domain.BillingPlan;
import io.cartogra.gateway.domain.Tenant;

import java.time.Instant;
import java.util.UUID;

public record TenantResponse(UUID id, String name, String slug, PlanResponse plan, long usersUsed, Instant createdAt) {
    public static TenantResponse from(Tenant t, BillingPlan plan, long usersUsed) {
        return new TenantResponse(t.id(), t.name(), t.slug(), PlanResponse.from(plan), usersUsed, t.createdAt());
    }
}
