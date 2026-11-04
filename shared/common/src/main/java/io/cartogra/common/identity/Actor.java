package io.cartogra.common.identity;

import java.util.UUID;

/**
 * Who performs a domain operation, resolved once at the controller boundary and passed explicitly.
 * {@code userId} is null for an unauthenticated caller; {@link #SYSTEM} (consumers, schedulers) passes every
 * access check and is recorded as a system change.
 */
public record Actor(UUID userId, boolean admin) {

    public static final Actor SYSTEM = new Actor(SystemActors.SYSTEM, true);
    public static final Actor ANONYMOUS = new Actor(null, false);

    public static Actor user(UUID userId) {
        return new Actor(userId, false);
    }

    public static Actor admin(UUID userId) {
        return new Actor(userId, true);
    }
}
