package io.cartogra.ingestion.repository;

import io.cartogra.ingestion.domain.Cluster;
import io.cartogra.ingestion.domain.ClusterStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClusterRepository {

    Cluster save(Cluster cluster);

    Optional<Cluster> findById(UUID tenantId, UUID id);

    List<Cluster> findAll(UUID tenantId, int limit, int offset);

    long count(UUID tenantId);

    void softDelete(UUID tenantId, UUID id);

    void updateStatus(UUID id, ClusterStatus status, String statusMessage);

    List<Cluster> findAllActive();
}
