package io.cartogra.gateway.domain;

import io.cartogra.common.identity.Roles;
import io.cartogra.gateway.api.dto.TokenResponse;
import io.cartogra.gateway.config.GuestProperties;
import io.cartogra.gateway.domain.exception.NotFoundException;
import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import io.cartogra.gateway.infrastructure.jwt.JwtTokenProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GuestService {

    private final GuestProperties props;
    private final JwtTokenProvider jwtTokenProvider;

    public GuestService(GuestProperties props, JwtTokenProvider jwtTokenProvider) {
        this.props = props;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** A fresh, unpersisted identity per mint: no user row, no refresh token; re-mint on expiry. */
    public TokenResponse mint() {
        UUID tenantId = props.tenantId();
        if (tenantId == null) {
            throw new NotFoundException("Guest demo is not enabled");
        }
        long ttlSeconds = props.tokenTtl().toSeconds();
        JwtClaims claims = new JwtClaims(UUID.randomUUID(), tenantId, null, "Demo guest", "guest",
            List.of(Roles.GUEST), Instant.now().plusSeconds(ttlSeconds));
        return new TokenResponse(jwtTokenProvider.issueAccessToken(claims), null, ttlSeconds);
    }
}
