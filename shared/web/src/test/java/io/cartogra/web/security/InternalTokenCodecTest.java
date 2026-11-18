package io.cartogra.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InternalTokenCodecTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();
    private static final InternalClaims CLAIMS = new InternalClaims(TENANT, UUID.randomUUID().toString(),
            List.of("ADMIN", "MEMBER"));

    private final InternalTokenCodec codec = InternalTokens.codec(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void verifiesATokenItSigned() {
        String token = codec.sign(CLAIMS, "registry");

        assertThat(codec.verify(token, "registry")).contains(CLAIMS);
    }

    @Test
    void rejectsAnExpiredToken() {
        String token = codec.sign(CLAIMS, "registry");
        var later = InternalTokens.codec(Clock.fixed(NOW.plusSeconds(61), ZoneOffset.UTC));

        assertThat(later.verify(token, "registry")).isEmpty();
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() {
        var other = new InternalTokenCodec(
                new InternalTokenProperties("another-key-for-tests-9d8e7f6a5b4c3d2e1f", Duration.ofSeconds(60)),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(codec.verify(other.sign(CLAIMS, "registry"), "registry")).isEmpty();
    }

    @Test
    void rejectsATokenForAnotherAudience() {
        assertThat(codec.verify(codec.sign(CLAIMS, "topology"), "registry")).isEmpty();
    }

    @Test
    void rejectsAMalformedToken() {
        assertThat(codec.verify("not-a-jwt", "registry")).isEmpty();
    }

    @Test
    void rejectsAnUnsignedToken() {
        String token = codec.sign(CLAIMS, "registry");
        String[] parts = token.split("\\.");
        String unsigned = "eyJhbGciOiJub25lIn0." + parts[1] + ".";

        assertThat(codec.verify(unsigned, "registry")).isEmpty();
    }
}
