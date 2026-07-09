package io.cartogra.gateway.api.dto;

import io.cartogra.gateway.domain.BillingPlan;

public record PlanResponse(
    String name,
    String slug,
    int maxServices,
    int maxUsers,
    int maxApiKeys,
    int maxScmConnections,
    int maxK8sClusters,
    boolean ssoEnabled,
    int rateLimitReplenish,
    int rateLimitBurst
) {
    public static PlanResponse from(BillingPlan p) {
        return new PlanResponse(
            p.name(), p.slug(), p.maxServices(), p.maxUsers(), p.maxApiKeys(),
            p.maxScmConnections(), p.maxK8sClusters(), p.ssoEnabled(),
            p.rateLimitReplenish(), p.rateLimitBurst());
    }
}
