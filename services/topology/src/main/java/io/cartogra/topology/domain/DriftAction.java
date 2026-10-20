package io.cartogra.topology.domain;

public enum DriftAction {
    ACCEPT_OBSERVED(DriftType.UNDECLARED, DriftResolution.ACCEPTED),
    REMOVE_DECLARED(DriftType.MISSING, DriftResolution.REMOVED),
    DISMISS(null, DriftResolution.DISMISSED);

    private final DriftType applicableType;
    private final DriftResolution resolution;

    DriftAction(DriftType applicableType, DriftResolution resolution) {
        this.applicableType = applicableType;
        this.resolution = resolution;
    }

    public boolean appliesTo(DriftType type) {
        return applicableType == null || applicableType == type;
    }

    public DriftResolution resolution() {
        return resolution;
    }
}
