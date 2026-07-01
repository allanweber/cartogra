package io.cartogra.registry.domain;

public enum ServiceTier {
    CRITICAL,
    STANDARD,
    EXPERIMENTAL;

    public String toDbValue() {
        return name().toLowerCase();
    }
}
