package io.cartogra.registry.domain;

public record PlanLimits(int maxServices, int maxScmConnections, int maxK8sClusters, int maxTeams, int maxApiKeys) {
    public static final int UNLIMITED = -1;

    public PlanLimits(int maxServices, int maxScmConnections, int maxK8sClusters, int maxTeams) {
        this(maxServices, maxScmConnections, maxK8sClusters, maxTeams, UNLIMITED);
    }
}
