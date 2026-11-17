package io.cartogra.contract.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Registry services as contract sees them: a name for the contract list and a tombstone once deleted. */
public interface ServiceProjectionRepository {

    /** Records the services' latest names in one batch; tombstoned services are left untouched. */
    void upsertNames(UUID tenantId, Map<UUID, String> names, Instant at);

    /** Returns false when the service was already tombstoned. */
    boolean tombstone(UUID tenantId, UUID serviceId, Instant at);

    boolean isDeleted(UUID tenantId, UUID serviceId);

    Optional<String> findName(UUID tenantId, UUID serviceId);

    /** Services of the tenant's live contracts with no name yet and not tombstoned. */
    List<UUID> findUnnamed(UUID tenantId, int limit);
}
