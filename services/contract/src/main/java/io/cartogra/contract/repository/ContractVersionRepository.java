package io.cartogra.contract.repository;

import io.cartogra.contract.domain.ContractVersion;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface ContractVersionRepository {

    void insert(ContractVersion version);

    Optional<ContractVersion> findLatest(UUID tenantId, UUID contractId);

    int softDeleteByContracts(UUID tenantId, Collection<UUID> contractIds, Instant deletedAt);
}
