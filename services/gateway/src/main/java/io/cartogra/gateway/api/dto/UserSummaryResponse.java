package io.cartogra.gateway.api.dto;

import io.cartogra.gateway.domain.User;

import java.util.List;
import java.util.UUID;

public record UserSummaryResponse(
    UUID id,
    String email,
    String name,
    List<String> roles,
    boolean disabled
) {
    public static UserSummaryResponse from(User u) {
        return new UserSummaryResponse(u.id(), u.email(), u.name(), u.roles(), u.disabledAt() != null);
    }
}
