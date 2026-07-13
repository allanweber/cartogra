package io.cartogra.gateway.api.dto;

import java.util.UUID;

public record InviteUserResponse(UUID invitationId, String email) {}
