package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.BlastRadius;

import java.util.UUID;

public record BlastRadiusResponse(
        UUID serviceId,
        BlastRadiusDirectionResponse upstream,
        BlastRadiusDirectionResponse downstream,
        int maxDepth
) {
    public static BlastRadiusResponse from(BlastRadius blastRadius) {
        return new BlastRadiusResponse(
                blastRadius.serviceId(),
                BlastRadiusDirectionResponse.from(blastRadius.upstream()),
                BlastRadiusDirectionResponse.from(blastRadius.downstream()),
                blastRadius.maxDepth());
    }
}
