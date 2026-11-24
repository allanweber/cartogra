package io.cartogra.registry.domain;

import java.util.Arrays;

public enum ApiKeyScope {
    CI_CHECK("ci:check");

    private final String value;

    ApiKeyScope(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ApiKeyScope parse(String raw) {
        return Arrays.stream(values())
                .filter(s -> s.value.equals(raw))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown API key scope: " + raw));
    }
}
