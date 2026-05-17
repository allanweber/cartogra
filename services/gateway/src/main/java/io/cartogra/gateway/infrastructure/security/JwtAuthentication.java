package io.cartogra.gateway.infrastructure.security;

import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.UUID;

public class JwtAuthentication extends AbstractAuthenticationToken {

    private final JwtClaims claims;

    public JwtAuthentication(JwtClaims claims) {
        super(claims.roles().stream()
            .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
            .toList());
        this.claims = claims;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return claims.userId().toString();
    }

    public UUID getUserId() {
        return claims.userId();
    }

    public UUID getTenantId() {
        return claims.tenantId();
    }

    public JwtClaims getClaims() {
        return claims;
    }
}
