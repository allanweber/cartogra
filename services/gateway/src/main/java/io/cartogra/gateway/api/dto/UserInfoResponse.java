package io.cartogra.gateway.api.dto;

import java.util.List;
import java.util.UUID;

public record UserInfoResponse(
    UUID sub,
    String email,
    UUID tenantId,
    List<String> roles
) {}
