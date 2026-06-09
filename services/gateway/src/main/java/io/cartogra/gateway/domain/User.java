package io.cartogra.gateway.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record User(
    UUID id,
    UUID tenantId,
    String email,
    String name,
    String authProvider,
    String authSubject,
    String passwordHash,
    boolean emailVerified,
    List<String> roles,
    String emailVerificationToken,
    Instant emailVerificationTokenExp,
    String passwordResetToken,
    Instant passwordResetTokenExp,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt
) {}
