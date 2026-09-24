package io.cartogra.ingestion.infrastructure.registry;

public record RegistryPlanLimits(int maxScmConnections, int maxK8sClusters) {
    public static final int UNLIMITED = -1;
}
