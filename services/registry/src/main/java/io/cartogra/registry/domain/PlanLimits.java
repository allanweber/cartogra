package io.cartogra.registry.domain;

public record PlanLimits(int maxServices, int maxScmConnections, int maxK8sClusters) {
    public static final int UNLIMITED = -1;
}
