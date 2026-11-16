package io.cartogra.contract.repository;

import java.time.Instant;
import java.util.UUID;

public interface DeletedServiceRepository {

    boolean exists(UUID tenantId, UUID serviceId);

    /** Returns false when the service was already recorded as deleted. */
    boolean insert(UUID tenantId, UUID serviceId, Instant deletedAt);
}
