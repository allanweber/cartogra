package io.cartogra.ingestion.repository;

import java.util.List;
import java.util.UUID;

public interface SpecPathRepository {

    /** The custom probe paths of the tenant, in their saved order. Empty when the tenant has no row. */
    List<String> findCustomPaths(UUID tenantId);

    void saveCustomPaths(UUID tenantId, List<String> paths);
}
