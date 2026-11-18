package io.cartogra.web.security;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Signs internal tokens for tests with {@link #KEY}, the value of {@code app.internal-token.key} in the test
 * configuration of each service.
 */
public final class InternalTokens {

    public static final String KEY = "test-internal-token-key-7f3a9c1e5b2d8f40";
    public static final String HEADER = InternalTokenCodec.HEADER;

    private static final InternalTokenCodec CODEC = codec(Clock.systemUTC());

    private InternalTokens() {}

    public static InternalTokenCodec codec(Clock clock) {
        return new InternalTokenCodec(new InternalTokenProperties(KEY, Duration.ofSeconds(60)), clock);
    }

    /** A user token; {@code roles} is a comma-separated list, as the gateway had in {@code X-User-Roles}. */
    public static String user(String audience, UUID tenantId, UUID userId, String roles) {
        return CODEC.sign(new InternalClaims(tenantId, userId.toString(), split(roles)), audience);
    }

    public static String user(String audience, UUID tenantId, String roles) {
        return user(audience, tenantId, UUID.randomUUID(), roles);
    }

    /**
     * A user token from the values a test had in its {@code X-Tenant-Id} / {@code X-User-Id} / {@code X-User-Roles}
     * headers: UUIDs or their strings. A null user is not a user id, as an unauthenticated caller was before.
     */
    public static String token(String audience, Object tenantId, Object userId, String roles) {
        String subject = userId == null ? "test:no-user" : UUID.fromString(String.valueOf(userId)).toString();
        return CODEC.sign(new InternalClaims(UUID.fromString(String.valueOf(tenantId)), subject, split(roles)), audience);
    }

    public static String service(String audience, UUID tenantId) {
        return CODEC.sign(new InternalClaims(tenantId, "service:test", List.of("SERVICE")), audience);
    }

    private static List<String> split(String roles) {
        return roles == null || roles.isBlank() ? List.of() : List.of(roles.split(","));
    }
}
