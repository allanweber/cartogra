package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.DriftAction;
import jakarta.validation.constraints.NotNull;

public record ResolveDriftRequest(@NotNull DriftAction action) {
}
