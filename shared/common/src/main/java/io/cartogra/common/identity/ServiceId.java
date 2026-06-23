package io.cartogra.common.identity;

import java.util.UUID;

public record ServiceId(UUID value) {
    public ServiceId { if (value == null) throw new IllegalArgumentException("ServiceId must not be null"); }
    public static ServiceId of(UUID value) { return new ServiceId(value); }
    public static ServiceId parse(String s) { return new ServiceId(UUID.fromString(s)); }
}
