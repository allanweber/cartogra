package io.cartogra.registry.api.dto;

import io.cartogra.registry.domain.PlanLimits;

public record PlanLimitsResponse(int maxScmConnections, int maxK8sClusters) {
    public static PlanLimitsResponse from(PlanLimits limits) {
        return new PlanLimitsResponse(limits.maxScmConnections(), limits.maxK8sClusters());
    }
}
