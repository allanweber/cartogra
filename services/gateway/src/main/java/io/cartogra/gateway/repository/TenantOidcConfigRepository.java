package io.cartogra.gateway.repository;

import io.cartogra.gateway.domain.TenantOidcConfig;

import java.util.Optional;
import java.util.UUID;

public interface TenantOidcConfigRepository {
    Optional<TenantOidcConfig> findByTenantId(UUID tenantId);
    Optional<TenantOidcConfig> findByIdAndTenantId(UUID id, UUID tenantId);
    TenantOidcConfig save(TenantOidcConfig config);
    void softDeleteByTenantId(UUID tenantId);
    void softDeleteByIdAndTenantId(UUID id, UUID tenantId);
}
