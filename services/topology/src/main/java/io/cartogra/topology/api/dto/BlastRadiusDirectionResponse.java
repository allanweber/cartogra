package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.BlastRadiusDirectionResult;

import java.util.List;

public record BlastRadiusDirectionResponse(
        List<BlastRadiusEntryResponse> entries,
        boolean depthTruncated,
        int nodesBeyondDepth,
        boolean nodeCapTruncated,
        int nodesBeyondCap
) {
    public static BlastRadiusDirectionResponse from(BlastRadiusDirectionResult result) {
        return new BlastRadiusDirectionResponse(
                result.entries().stream().map(BlastRadiusEntryResponse::from).toList(),
                result.depthTruncated(), result.nodesBeyondDepth(),
                result.nodeCapTruncated(), result.nodesBeyondCap());
    }
}
