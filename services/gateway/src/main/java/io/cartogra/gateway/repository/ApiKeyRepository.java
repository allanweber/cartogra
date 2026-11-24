package io.cartogra.gateway.repository;

import io.cartogra.gateway.domain.ApiKey;

import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository {

    /** Finds a key that is not revoked, not expired and not deleted. */
    Optional<ApiKey> findActiveByHash(String keyHash);

    /** Sets {@code last_used_at} at most one time per minute. Returns true if the row changed. */
    boolean touchLastUsed(UUID id);
}
