package io.cartogra.contract.repository;

import io.cartogra.contract.domain.ApiContract;
import io.cartogra.contract.domain.ContractSummary;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContractRepository {

    void insert(ApiContract contract);

    Optional<ApiContract> findLive(UUID tenantId, UUID id);

    /** {@link #findLive} that also row-locks the contract until the transaction ends, serializing version numbering. */
    Optional<ApiContract> lockLive(UUID tenantId, UUID id);

    Optional<ApiContract> findLiveByService(UUID tenantId, UUID serviceId);

    void touch(UUID tenantId, UUID id, Instant updatedAt);

    /** Soft-deletes the service's live contracts and returns their ids; empty when none were live. */
    List<UUID> softDeleteByService(UUID tenantId, UUID serviceId, Instant deletedAt);

    /**
     * Live contracts with their latest version, most recently changed first; {@code q} matches the service name and
     * {@code breaking}, when set, the latest version's verdict (an unchecked version matches neither value).
     */
    List<ContractSummary> listLive(UUID tenantId, @Nullable String q, @Nullable Boolean breaking, int limit,
                                   int offset);

    long countLive(UUID tenantId, @Nullable String q, @Nullable Boolean breaking);
}
