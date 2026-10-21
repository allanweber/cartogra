package io.cartogra.gateway.repository;

import io.cartogra.gateway.domain.RefreshToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {
    Optional<RefreshToken> findActiveByHash(String tokenHash);
    Optional<RefreshToken> findActiveByPreviousHash(String tokenHash);

    /** Atomically swaps {@code currentHash} for {@code newHash} on the active session; empty when no
     *  active session currently holds {@code currentHash} (unknown, expired, revoked, or already rotated). */
    Optional<RefreshToken> rotate(String currentHash, String newHash, Instant expiresAt);

    RefreshToken save(RefreshToken token);
    void revoke(UUID id);
    void revokeAllForUser(UUID userId);
    int deleteExpiredAndRevoked(java.time.Duration retentionPeriod);
}
