package io.cartogra.gateway.domain;

import java.util.Set;
import java.util.UUID;

/** An active API key: the identity that the gateway needs after the lookup. */
public record ApiKey(UUID id, UUID tenantId, Set<String> scopes) {

    public ApiKey {
        scopes = Set.copyOf(scopes);
    }
}
