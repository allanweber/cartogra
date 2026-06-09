package io.cartogra.gateway.api.dto;

import java.util.List;
import java.util.UUID;

public record UserInfoResponse(
    UUID id,
    String email,
    String name,
    String authProvider,
    UUID tenantId,
    List<String> roles
) {}
