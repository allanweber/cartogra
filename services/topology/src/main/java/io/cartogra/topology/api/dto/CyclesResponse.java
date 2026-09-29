package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.Cycles;

import java.util.List;

public record CyclesResponse(List<CycleResponse> cycles, boolean truncated) {
    public static CyclesResponse from(Cycles cycles) {
        return new CyclesResponse(cycles.cycles().stream().map(CycleResponse::from).toList(), cycles.truncated());
    }
}
