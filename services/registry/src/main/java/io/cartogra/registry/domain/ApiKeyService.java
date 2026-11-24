package io.cartogra.registry.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.common.audit.AuditEvent;
import io.cartogra.common.audit.AuditEventPort;
import io.cartogra.common.audit.AuditKind;
import io.cartogra.common.identity.Actor;
import io.cartogra.common.identity.Roles;
import io.cartogra.registry.domain.exception.ApiKeyNotFoundException;
import io.cartogra.registry.domain.exception.PlanLimitExceededException;
import io.cartogra.registry.repository.ApiKeyRepository;
import io.cartogra.web.lock.AdvisoryLockRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ApiKeyService {

    static final String KEY_PREFIX = "cgk_";
    static final int RANDOM_BYTES = 32;
    static final int DISPLAY_CHARS = 8;
    static final int MAX_NAME_LENGTH = 100;
    static final int MAX_LIST_LIMIT = 200;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeyRepository;
    private final PlanLimitService planLimitService;
    private final AdvisoryLockRepository advisoryLockRepository;
    private final AuditEventPort auditEventPort;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, PlanLimitService planLimitService,
                         AdvisoryLockRepository advisoryLockRepository, AuditEventPort auditEventPort) {
        this.apiKeyRepository = apiKeyRepository;
        this.planLimitService = planLimitService;
        this.advisoryLockRepository = advisoryLockRepository;
        this.auditEventPort = auditEventPort;
    }

    @PreAuthorize(Roles.ADMIN_ONLY)
    @Transactional
    public CreatedApiKey create(UUID tenantId, Actor actor, String rawName, List<String> rawScopes,
                                Instant expiresAt) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Name must have 1 to " + MAX_NAME_LENGTH + " characters");
        }
        if (rawScopes == null || rawScopes.isEmpty()) {
            throw new IllegalArgumentException("At least one scope is required");
        }
        List<String> scopes = rawScopes.stream()
                .map(ApiKeyScope::parse)
                .distinct()
                .map(ApiKeyScope::value)
                .toList();
        Instant now = Instant.now();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
        if (actor.userId() == null) {
            throw new IllegalArgumentException("A user must create the key");
        }

        long lockKey = AdvisoryLockRepository.lockKey("cartogra.registry.api-key-create", tenantId);
        if (!advisoryLockRepository.tryAcquireLock(lockKey)) {
            throw new IllegalStateException("Could not acquire plan-limit lock for tenant " + tenantId);
        }
        try {
            int maxApiKeys = planLimitService.getLimits(tenantId).maxApiKeys();
            if (maxApiKeys != PlanLimits.UNLIMITED && apiKeyRepository.countActive(tenantId) >= maxApiKeys) {
                throw new PlanLimitExceededException("api keys", maxApiKeys);
            }
            String key = generateKey();
            ApiKey saved = apiKeyRepository.insert(new ApiKey(
                    UUID.randomUUID(), tenantId, name, hash(key), displayPrefix(key), scopes, expiresAt,
                    null, null, actor.userId(), now, now));
            audit(saved, "created", actor);
            return new CreatedApiKey(saved, key);
        } finally {
            advisoryLockRepository.releaseLock(lockKey);
        }
    }

    public PageResult<ApiKey> list(UUID tenantId, int limit, int offset) {
        int boundedLimit = Math.clamp(limit, 1, MAX_LIST_LIMIT);
        int boundedOffset = Math.max(offset, 0);
        List<ApiKey> items = apiKeyRepository.findAll(tenantId, boundedLimit, boundedOffset);
        return PageResult.of(items, apiKeyRepository.count(tenantId), boundedLimit, boundedOffset);
    }

    @PreAuthorize(Roles.ADMIN_ONLY)
    @Transactional
    public void revoke(UUID tenantId, Actor actor, UUID id) {
        ApiKey existing = apiKeyRepository.findById(tenantId, id)
                .orElseThrow(() -> new ApiKeyNotFoundException(id));
        if (existing.revokedAt() == null && apiKeyRepository.revoke(tenantId, id)) {
            audit(existing, "revoked", actor);
        }
    }

    static String generateKey() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String displayPrefix(String key) {
        return key.substring(0, KEY_PREFIX.length() + DISPLAY_CHARS);
    }

    static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void audit(ApiKey key, String action, Actor actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", key.name());
        payload.put("prefix", key.keyPrefix());
        payload.put("scopes", key.scopes());
        payload.put("expiresAt", key.expiresAt() == null ? null : key.expiresAt().toString());
        auditEventPort.record(AuditEvent.of(key.tenantId(), AuditKind.API_KEY, key.id(), action, actor, payload));
    }
}
