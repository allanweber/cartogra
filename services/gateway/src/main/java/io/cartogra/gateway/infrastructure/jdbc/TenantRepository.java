package io.cartogra.gateway.infrastructure.jdbc;

import io.cartogra.gateway.domain.Tenant;

public interface TenantRepository {
    Tenant save(Tenant tenant);
}
