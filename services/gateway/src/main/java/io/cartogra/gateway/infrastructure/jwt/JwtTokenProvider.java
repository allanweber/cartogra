package io.cartogra.gateway.infrastructure.jwt;

import io.cartogra.gateway.config.JwtConfig;
import io.cartogra.gateway.domain.exception.UnauthorizedException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

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
        var builder = JwtClaimsSet.builder()
            .subject(claims.userId().toString())
            .claim("tid", claims.tenantId().toString())
            .claim("email", claims.email())
            .claim("auth_provider", claims.authProvider())
            .claim("roles", claims.roles())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(config.accessTokenExpirySeconds()));
        if (claims.name() != null) {
            builder.claim("name", claims.name());
        }
        return encoder.encode(JwtEncoderParameters.from(header, builder.build())).getTokenValue();
    }

    public String issueRefreshToken() {
        return UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    }

    public JwtClaims decode(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            return new JwtClaims(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString("tid")),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                jwt.getClaimAsString("auth_provider"),
                jwt.getClaimAsStringList("roles"),
                jwt.getExpiresAt()
            );
        } catch (JwtException e) {
            throw new UnauthorizedException("Invalid or expired token");
        }
    }
}
