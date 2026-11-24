package io.cartogra.registry.repository;

import io.cartogra.registry.domain.ApiKey;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository {

    ApiKey insert(ApiKey apiKey);

    Optional<ApiKey> findById(UUID tenantId, UUID id);

    List<ApiKey> findAll(UUID tenantId, int limit, int offset);

    long count(UUID tenantId);

    long countActive(UUID tenantId);

    boolean revoke(UUID tenantId, UUID id);
}
