package io.cartogra.ingestion.domain;

import java.util.List;
import java.util.Map;

public record OwnershipMap(
        List<String> ownerTeams,
        Map<String, List<String>> pathOwners
) {}
