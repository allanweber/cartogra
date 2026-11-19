package io.cartogra.contract.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/** A {@link ContractVersion} without its spec text, for listings. */
public record VersionHeader(
        UUID id,
        UUID contractId,
        int versionNumber,
        @Nullable String label,
        String specHash,
        @Nullable Boolean breaking,
        Instant createdAt
) {
    public static VersionHeader of(ContractVersion v) {
        return new VersionHeader(v.id(), v.contractId(), v.versionNumber(), v.label(), v.specHash(), v.breaking(),
                v.createdAt());
    }
}
