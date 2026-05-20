package io.cartogra.gateway.infrastructure.jwt;

import io.cartogra.gateway.config.JwtConfig;
import io.cartogra.gateway.domain.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        String secret = "test-secret-32-bytes-padding-here!";
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "HmacSHA256");

        OctetSequenceKey jwk = new OctetSequenceKey.Builder(keyBytes)
            .algorithm(JWSAlgorithm.HS256)
            .build();
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtConfig config = new JwtConfig(secret, 900L, 2592000L);

        provider = new JwtTokenProvider(encoder, decoder, config);
    }

    @Test
    void issueAndDecodeRoundTrip() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        JwtClaims claims = new JwtClaims(userId, tenantId, "user@example.com", List.of("VIEWER"), null);

        String token = provider.issueAccessToken(claims);
        JwtClaims decoded = provider.decode(token);

        assertThat(decoded).isNotNull();
        assertThat(decoded.userId()).isEqualTo(userId);
        assertThat(decoded.tenantId()).isEqualTo(tenantId);
        assertThat(decoded.email()).isEqualTo("user@example.com");
        assertThat(decoded.roles()).containsExactly("VIEWER");
    }

    @Test
    void issueRefreshTokenIsLongAndRandom() {
        String t1 = provider.issueRefreshToken();
        String t2 = provider.issueRefreshToken();

        assertThat(t1).hasSize(64);
        assertThat(t2).hasSize(64);
        assertThat(t1).isNotEqualTo(t2);
    }

    @Test
    void decodeInvalidTokenThrows() {
        assertThatThrownBy(() -> provider.decode("not.a.jwt"))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void decodeTamperedTokenThrows() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        JwtClaims claims = new JwtClaims(userId, tenantId, "user@example.com", List.of("VIEWER"), null);
        String token = provider.issueAccessToken(claims);

        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertThatThrownBy(() -> provider.decode(tampered))
            .isInstanceOf(UnauthorizedException.class);
    }
}
