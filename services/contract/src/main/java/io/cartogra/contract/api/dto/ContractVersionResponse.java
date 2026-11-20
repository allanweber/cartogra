package io.cartogra.contract.api.dto;

import io.cartogra.contract.domain.ContractVersion;
import io.cartogra.contract.domain.VersionHeader;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record ContractVersionResponse(
        UUID id,
        UUID contractId,
        int versionNumber,
        @Nullable String label,
        String specHash,
        @Nullable Boolean breaking,
        Instant createdAt,
        String source,
        @Nullable String repositoryFullName,
        @Nullable String filePath
) {
    public static ContractVersionResponse from(ContractVersion v) {
        return from(VersionHeader.of(v));
    }

    public static ContractVersionResponse from(VersionHeader v) {
        return new ContractVersionResponse(v.id(), v.contractId(), v.versionNumber(), v.label(), v.specHash(),
                v.breaking(), v.createdAt(), v.source(), v.repositoryFullName(), v.filePath());
    }
}
