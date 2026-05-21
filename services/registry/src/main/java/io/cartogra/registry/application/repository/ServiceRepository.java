package io.cartogra.registry.application.repository;

import io.cartogra.registry.application.dto.ServiceFilter;
import io.cartogra.registry.domain.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRepository {

    Optional<Service> findById(UUID tenantId, UUID id);

    List<Service> findAll(UUID tenantId, ServiceFilter filter, int limit, int offset);

    long count(UUID tenantId, ServiceFilter filter);

    List<Service> findOrphaned(UUID tenantId, int limit, int offset);

    Service save(Service service);

    void softDelete(UUID tenantId, UUID id);

    boolean existsByName(UUID tenantId, String name, UUID excludeId);

    Optional<Service> findByRepositoryPath(UUID tenantId, String repositoryPath);
}
