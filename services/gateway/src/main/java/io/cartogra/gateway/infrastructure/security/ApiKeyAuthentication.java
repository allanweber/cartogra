package io.cartogra.gateway.infrastructure.security;

import io.cartogra.gateway.domain.ApiKey;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** The authentication of a request that carries an active API key. Each scope gives one role. */
public class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private final ApiKey key;
    private final List<String> roles;

    public ApiKeyAuthentication(ApiKey key) {
        super(key.scopes().stream().map(ApiKeyAuthentication::roleOf).sorted()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
        this.key = key;
        this.roles = key.scopes().stream().map(ApiKeyAuthentication::roleOf).sorted().toList();
        setAuthenticated(true);
    }

    /** {@code ci:check} becomes {@code CI_CHECK}. */
    public static String roleOf(String scope) {
        return scope.toUpperCase(Locale.ROOT).replace(':', '_');
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public ApiKey getPrincipal() {
        return key;
    }

    @Override
    public String getName() {
        return subject();
    }

    public UUID getKeyId() {
        return key.id();
    }

    public UUID getTenantId() {
        return key.tenantId();
    }

    public List<String> getRoles() {
        return roles;
    }

    /** The subject of the internal token: {@code apikey:<keyId>}. */
    public String subject() {
        return "apikey:" + key.id();
    }

    public boolean hasScope(String scope) {
        return key.scopes().contains(scope);
    }
}
