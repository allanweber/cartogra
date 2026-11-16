package io.cartogra.contract.api.dto;

import io.cartogra.contract.domain.PublishedContract;

import java.time.Instant;
import java.util.UUID;

public record ContractResponse(
        UUID id,
        UUID serviceId,
        String specType,
        Instant createdAt,
        Instant updatedAt,
        ContractVersionResponse latestVersion
) {
    public static ContractResponse from(PublishedContract p) {
        var c = p.contract();
        return new ContractResponse(c.id(), c.serviceId(), c.specType(), c.createdAt(), c.updatedAt(),
                ContractVersionResponse.from(p.latestVersion()));
    }
}
