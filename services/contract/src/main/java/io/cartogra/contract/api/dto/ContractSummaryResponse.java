package io.cartogra.contract.api.dto;

import io.cartogra.contract.domain.ContractSummary;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record ContractSummaryResponse(
        UUID id,
        UUID serviceId,
        @Nullable String serviceName,
        ContractVersionResponse latestVersion,
        Instant updatedAt
) {
    public static ContractSummaryResponse from(ContractSummary s) {
        return new ContractSummaryResponse(s.id(), s.serviceId(), s.serviceName(),
                ContractVersionResponse.from(s.latestVersion()), s.updatedAt());
    }
}
