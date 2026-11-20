package io.cartogra.contract.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable revision of a contract. {@code breaking} is the engine verdict against the previous version; it is
 * null for a version stored before 5.3. {@code source} is {@link #SOURCE_MANUAL} or {@link #SOURCE_DISCOVERY}; a
 * discovered version also names the repository and file that discovery found.
 */
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
        Instant createdAt,
        String source,
        @Nullable UUID discoveredSpecId,
        @Nullable String repositoryFullName,
        @Nullable String filePath
) {
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_DISCOVERY = "discovery";
}
