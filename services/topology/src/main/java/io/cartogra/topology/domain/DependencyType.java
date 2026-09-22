package io.cartogra.topology.domain;

public enum DependencyType {
    DECLARED,
    OBSERVED;

    public String toDbValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static DependencyType fromDbValue(String value) {
        return valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }
}
