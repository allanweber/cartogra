package io.cartogra.registry.api;

import io.cartogra.common.identity.Roles;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;

/** A GUEST caller reads the public sandbox tenant; responses to it carry no person identifiers (ADR-0019 §8). */
final class GuestCaller {

    private static final String GUEST_AUTHORITY = "ROLE_" + Roles.GUEST;

    private GuestCaller() {
    }

    static boolean is(@Nullable Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> GUEST_AUTHORITY.equals(a.getAuthority()));
    }
}
