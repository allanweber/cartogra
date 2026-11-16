package io.cartogra.contract.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record ContractVersion(
        UUID id,
        UUID tenantId,
        UUID contractId,
        int versionNumber,
        @Nullable String label,
        String specRaw,
        String specCanonical,
        String specHash,
        Instant createdAt
) {}
