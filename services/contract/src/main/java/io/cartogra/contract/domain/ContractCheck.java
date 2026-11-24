package io.cartogra.contract.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The stored result of one CI check. It is not a Contract Version. */
public record ContractCheck(
        UUID id,
        UUID tenantId,
        UUID serviceId,
        UUID contractId,
        UUID baselineVersionId,
        String candidateHash,
        boolean breaking,
        List<CompatibilityChange> changes,
        List<UUID> affectedConsumerIds,
        UUID apiKeyId,
        Instant createdAt
) {}
