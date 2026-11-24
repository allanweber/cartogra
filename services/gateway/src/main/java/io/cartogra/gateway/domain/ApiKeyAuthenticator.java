package io.cartogra.gateway.domain;

import io.cartogra.gateway.repository.ApiKeyRepository;
import io.cartogra.web.tenant.TenantDb;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Finds the active API key for a raw key. There is no cache, so a revoke works on the next request. The
 * lookup runs as system because the tenant is not known before the lookup.
 */
@Service
public class ApiKeyAuthenticator {

    private final ApiKeyRepository apiKeys;

    public ApiKeyAuthenticator(ApiKeyRepository apiKeys) {
        this.apiKeys = apiKeys;
    }

    /** Returns the key if it is active, and records its use (at most one time per minute). */
    public Optional<ApiKey> authenticate(String rawKey) {
        return TenantDb.asSystem(() -> {
            Optional<ApiKey> key = apiKeys.findActiveByHash(hash(rawKey));
            key.ifPresent(k -> apiKeys.touchLastUsed(k.id()));
            return key;
        });
    }

    public static String hash(String rawKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
