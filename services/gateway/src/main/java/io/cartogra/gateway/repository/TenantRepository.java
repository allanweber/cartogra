package io.cartogra.gateway.repository;

import io.cartogra.gateway.domain.Tenant;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository {
    Tenant save(Tenant tenant);
    Optional<Tenant> findByTenantId(UUID tenantId);
}
