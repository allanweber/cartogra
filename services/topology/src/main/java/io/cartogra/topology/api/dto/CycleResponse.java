package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.Cycle;

import java.util.List;
import java.util.UUID;

public record CycleResponse(List<UUID> members, int length) {
    public static CycleResponse from(Cycle cycle) {
        return new CycleResponse(cycle.members(), cycle.members().size());
    }
}
