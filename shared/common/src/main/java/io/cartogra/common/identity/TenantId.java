package io.cartogra.common.identity;

import java.util.UUID;

public record TenantId(UUID value) {
    public TenantId { if (value == null) throw new IllegalArgumentException("TenantId must not be null"); }
    public static TenantId of(UUID value) { return new TenantId(value); }
    public static TenantId parse(String s) { return new TenantId(UUID.fromString(s)); }
}