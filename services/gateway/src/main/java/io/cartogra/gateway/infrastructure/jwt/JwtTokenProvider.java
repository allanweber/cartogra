package io.cartogra.gateway.infrastructure.jwt;

import io.cartogra.gateway.config.JwtConfig;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final JwtConfig config;

    public JwtTokenProvider(JwtEncoder encoder, JwtDecoder decoder, JwtConfig config) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.config = config;
    }

    public String issueAccessToken(JwtClaims claims) {
        Instant now = Instant.now();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claimsSet = JwtClaimsSet.builder()
            .subject(claims.userId().toString())
            .claim("tid", claims.tenantId().toString())
            .claim("email", claims.email())
            .claim("roles", claims.roles())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(config.accessTokenExpirySeconds()))
            .build();
        return encoder.encode(JwtEncoderParameters.from(header, claimsSet)).getTokenValue();
    }

    public String issueRefreshToken() {
        return UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    }

    public Mono<JwtClaims> decode(String token) {
        return Mono.fromCallable(() -> {
            Jwt jwt = decoder.decode(token);
            return new JwtClaims(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString("tid")),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsStringList("roles"),
                jwt.getExpiresAt()
            );
        });
    }
}
