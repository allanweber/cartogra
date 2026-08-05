package io.cartogra.topology.domain;

public enum DriftType {
    UNDECLARED,
    MISSING;

    public String toDbValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
