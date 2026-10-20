package io.cartogra.topology.domain;

import java.util.Locale;

public enum DriftResolution {
    ACCEPTED,
    REMOVED,
    DISMISSED,
    AUTO;

    public String toDbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static DriftResolution fromDbValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
