package io.cartogra.topology.domain;

public enum DependencyType {
    DECLARED,
    OBSERVED;

    public String toDbValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
