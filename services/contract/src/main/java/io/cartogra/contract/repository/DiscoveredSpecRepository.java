package io.cartogra.contract.repository;

import io.cartogra.contract.domain.DiscoveredSpec;
import io.cartogra.contract.domain.DiscoveredSpecStatus;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiscoveredSpecRepository {

    /**
     * Records the spec as the last spec seen at its (tenant, repository, path), in the transaction of the caller.
     * If the last spec seen there has the same content hash, it gives that row and inserts nothing. Otherwise it
     * inserts the spec as PENDING, and the older row is no longer the latest; an older PENDING row becomes SUPERSEDED.
     */
    DiscoveredSpec recordLatest(DiscoveredSpec spec);

    /**
     * Inserts a PENDING copy of {@code latest} as the new latest spec at its repository and path, in the transaction
     * of the caller. The old row keeps its status and is no longer the latest. The caller must hold the lock that
     * {@link #recordLatest} takes on {@code latest}.
     */
    DiscoveredSpec reopenLatest(DiscoveredSpec latest);

    /** The PENDING specs of the repository, oldest first. Only the latest spec of each path can be PENDING. */
    List<DiscoveredSpec> findPending(UUID tenantId, String repositoryFullName);

    /** Locks the row for the transaction of the caller; empty when the spec is no longer PENDING. */
    Optional<DiscoveredSpec> lockPending(UUID tenantId, UUID id);

    /** Moves a PENDING spec to {@code status}; a spec in a final state is left as it is. */
    void markStatus(UUID tenantId, UUID id, DiscoveredSpecStatus status, @Nullable UUID serviceId,
                    @Nullable UUID contractVersionId, List<String> errors);
}
