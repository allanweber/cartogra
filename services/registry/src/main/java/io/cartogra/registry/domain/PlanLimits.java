package io.cartogra.registry.domain;

public record PlanLimits(int maxServices, int maxScmConnections, int maxK8sClusters, int maxTeams) {
    public static final int UNLIMITED = -1;
}
