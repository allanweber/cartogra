package io.cartogra.registry.application.repository;

import io.cartogra.registry.domain.ScmConnection;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScmConnectionRepository {

    Optional<ScmConnection> findById(UUID tenantId, UUID id);

    List<ScmConnection> findAll(UUID tenantId, int limit, int offset);

    long count(UUID tenantId);

    ScmConnection save(ScmConnection connection);

    void softDelete(UUID tenantId, UUID id);
}
