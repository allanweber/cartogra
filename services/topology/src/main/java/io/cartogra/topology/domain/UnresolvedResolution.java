package io.cartogra.topology.domain;

import java.util.Locale;

public enum UnresolvedResolution {
    DISMISSED,
    REGISTERED;

    public String toDbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static UnresolvedResolution fromDbValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
