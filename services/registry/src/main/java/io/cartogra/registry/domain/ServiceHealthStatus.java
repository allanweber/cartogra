package io.cartogra.registry.domain;

public enum ServiceHealthStatus {
    HEALTHY,
    DEGRADED,
    UNHEALTHY,
    PROBE_AUTH_FAILED,
    UNKNOWN;

    public static ServiceHealthStatus fromString(String value) {
        return switch (value.toLowerCase()) {
            case "healthy" -> HEALTHY;
            case "degraded" -> DEGRADED;
            case "unhealthy" -> UNHEALTHY;
            case "probe_auth_failed" -> PROBE_AUTH_FAILED;
            default -> UNKNOWN;
        };
    }

    public String toDbValue() {
        return name().toLowerCase();
    }
}
