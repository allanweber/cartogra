package io.cartogra.ingestion.application.port.out;

import java.util.List;
import java.util.Map;

public record OwnershipMap(
        List<String> ownerTeams,
        Map<String, List<String>> pathOwners
) {}
