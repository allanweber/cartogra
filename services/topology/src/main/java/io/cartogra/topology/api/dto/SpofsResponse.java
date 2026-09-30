package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.SpofResult;

import java.util.List;

public record SpofsResponse(int threshold, String rationale, List<SpofResponse> items) {
    public static SpofsResponse from(SpofResult result) {
        return new SpofsResponse(result.threshold(), result.rationale(),
                result.items().stream().map(SpofResponse::from).toList());
    }
}
