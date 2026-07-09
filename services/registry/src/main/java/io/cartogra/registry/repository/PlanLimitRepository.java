package io.cartogra.registry.repository;

import io.cartogra.registry.domain.PlanLimits;

import java.util.UUID;

public interface PlanLimitRepository {
    PlanLimits findByTenantId(UUID tenantId);
}
