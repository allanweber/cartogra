package io.cartogra.gateway.domain;

import java.time.Instant;
import java.util.UUID;

public record BillingPlan(
    UUID id,
    String name,
    String slug,
    int maxServices,
    int maxUsers,
    int maxApiKeys,
    int maxScmConnections,
    int maxK8sClusters,
    boolean ssoEnabled,
    int rateLimitReplenish,
    int rateLimitBurst,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt
) {
    public static final int UNLIMITED = -1;
}
