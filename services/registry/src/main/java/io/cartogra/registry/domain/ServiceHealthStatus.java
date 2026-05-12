package io.cartogra.registry.domain;

public enum ServiceHealthStatus {
    HEALTHY,
    DEGRADED,
    UNKNOWN;

    public static ServiceHealthStatus fromString(String value) {
        return switch (value.toLowerCase()) {
            case "healthy" -> HEALTHY;
            case "degraded" -> DEGRADED;
            default -> UNKNOWN;
        };
    }

    public String toDbValue() {
        return name().toLowerCase();
    }
}
