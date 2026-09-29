package io.cartogra.topology.domain;

import java.util.UUID;

public record BlastRadius(
        UUID serviceId,
        BlastRadiusDirectionResult upstream,
        BlastRadiusDirectionResult downstream,
        int maxDepth
) {
}
