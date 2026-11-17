package io.cartogra.contract.repository;

import io.cartogra.contract.domain.ContractVersion;
import io.cartogra.contract.domain.VersionHeader;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContractVersionRepository {

    void insert(ContractVersion version);

    Optional<ContractVersion> findLatest(UUID tenantId, UUID contractId);

    int softDeleteByContracts(UUID tenantId, Collection<UUID> contractIds, Instant deletedAt);

    Optional<ContractVersion> findByNumber(UUID tenantId, UUID contractId, int versionNumber);

    /** Newest first. */
    List<VersionHeader> list(UUID tenantId, UUID contractId, int limit, int offset);

    long count(UUID tenantId, UUID contractId);
}
