package io.cartogra.ingestion.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record OwnershipResolvedPayload(
        UUID tenantId,
        UUID connectionId,
        String repositoryFullName,
        List<String> ownerTeams,
        Map<String, List<String>> pathOwners
) {}
