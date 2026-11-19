package io.cartogra.web.security;

import java.util.List;
import java.util.UUID;

/** The identity in an internal token: a user id or {@code service:<name>} as the subject. */
public record InternalClaims(UUID tenantId, String subject, List<String> roles) {

    public InternalClaims {
        roles = List.copyOf(roles);
    }
}
