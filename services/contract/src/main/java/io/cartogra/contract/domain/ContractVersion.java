package io.cartogra.contract.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/** {@code breaking} is the engine verdict against the previous version; null for a version stored before 5.3. */
public record ContractVersion(
        UUID id,
        UUID tenantId,
        UUID contractId,
        int versionNumber,
        @Nullable String label,
        String specRaw,
        String specCanonical,
        String specHash,
        @Nullable Boolean breaking,
        Instant createdAt
) {}
