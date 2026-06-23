package io.cartogra.gateway.repository;

import io.cartogra.gateway.domain.Tenant;

public interface TenantRepository {
    Tenant save(Tenant tenant);
}
