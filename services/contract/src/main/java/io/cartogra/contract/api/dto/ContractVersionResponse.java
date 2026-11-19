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
        Instant createdAt
) {
    public static ContractVersionResponse from(ContractVersion v) {
        return new ContractVersionResponse(v.id(), v.contractId(), v.versionNumber(), v.label(), v.specHash(),
                v.breaking(), v.createdAt());
    }

    public static ContractVersionResponse from(VersionHeader v) {
        return new ContractVersionResponse(v.id(), v.contractId(), v.versionNumber(), v.label(), v.specHash(),
                v.breaking(), v.createdAt());
    }
}
