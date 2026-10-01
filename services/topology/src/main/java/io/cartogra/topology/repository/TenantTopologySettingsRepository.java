package io.cartogra.topology.repository;

import java.util.Optional;
import java.util.UUID;

public interface TenantTopologySettingsRepository {

    /** Absent when the tenant has no settings row — callers fall back to the application default. */
    Optional<Integer> findSpofThreshold(UUID tenantId);
}
