package io.cartogra.contract.domain;

import org.jspecify.annotations.Nullable;

/**
 * One classified change. {@code method} is null for a path rule; {@code location} is null for a path or operation
 * rule, else a field path such as {@code requestBody.items[].sku}, {@code responses.200.id} or {@code query.currency}.
 */
public record CompatibilityChange(
        CompatibilityRule rule,
        Direction direction,
        @Nullable String method,
        String path,
        @Nullable String location,
        String message
) {
    public enum Direction { REQUEST, RESPONSE, OPERATION }

    public boolean breaking() {
        return rule.breaking();
    }
}
