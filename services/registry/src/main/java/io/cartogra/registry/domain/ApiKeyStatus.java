package io.cartogra.registry.domain;

public enum ApiKeyStatus {
    ACTIVE, EXPIRED, REVOKED;

    public String value() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
