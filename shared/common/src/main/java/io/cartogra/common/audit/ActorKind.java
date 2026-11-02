package io.cartogra.common.audit;

import java.util.Locale;

public enum ActorKind {
    USER,
    SYSTEM;

    public String toDbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ActorKind fromDbValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
