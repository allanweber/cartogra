package io.cartogra.ingestion.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.ingestion.api.dto.ScmConnectionRequest;
import io.cartogra.ingestion.domain.exception.PlanLimitExceededException;
import io.cartogra.ingestion.repository.ScmConnectionRepository;
import io.cartogra.ingestion.domain.exception.ScmConnectionNotFoundException;
import io.cartogra.ingestion.infrastructure.k8s.CredentialEncryptor;
import io.cartogra.ingestion.infrastructure.kafka.SyncCommandProducer;
import io.cartogra.ingestion.infrastructure.registry.RegistryPlanLimitClient;
import io.cartogra.ingestion.infrastructure.registry.RegistryPlanLimits;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SCM connection CRUD plus on-demand sync triggering. The controller talks only
 * to this service; persistence stays behind {@link ScmConnectionRepository}.
 */
@Service
public class ScmConnectionService {

    private static final int DEFAULT_POLL_INTERVAL_MINUTES = 15;
    static final Set<String> SECRET_KEYS = Set.of("token", "pat", "webhookSecret");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> CONFIG_MAP_TYPE = new TypeReference<>() {};

    private final ScmConnectionRepository repository;
    private final SyncCommandProducer syncCommandProducer;
    private final RegistryPlanLimitClient planLimitClient;
    private final CredentialEncryptor credentialEncryptor;

    public ScmConnectionService(ScmConnectionRepository repository, SyncCommandProducer syncCommandProducer,
                                RegistryPlanLimitClient planLimitClient, CredentialEncryptor credentialEncryptor) {
        this.repository = repository;
        this.syncCommandProducer = syncCommandProducer;
        this.planLimitClient = planLimitClient;
        this.credentialEncryptor = credentialEncryptor;
    }

    @Transactional
    public ScmConnection create(UUID tenantId, ScmConnectionRequest req) {
        if (req.provider() == null || req.provider().isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        int maxScmConnections = planLimitClient.fetchLimits(tenantId)
                .map(RegistryPlanLimits::maxScmConnections)
                .orElse(RegistryPlanLimits.UNLIMITED);
        if (maxScmConnections != RegistryPlanLimits.UNLIMITED && repository.count(tenantId) >= maxScmConnections) {
            throw new PlanLimitExceededException("scm connections", maxScmConnections);
        }
        Instant now = Instant.now();
        boolean scheduler = Boolean.TRUE.equals(req.syncScheduler());
        int interval = req.pollIntervalMinutes() != null ? req.pollIntervalMinutes() : DEFAULT_POLL_INTERVAL_MINUTES;
        boolean webhook = Boolean.TRUE.equals(req.webhookEnabled());
        String config = req.config() != null ? req.config() : "{}";
        if (webhook && !hasWebhookSecret(config)) {
            throw new IllegalArgumentException("webhookSecret is required in config when webhookEnabled is true");
        }
        config = encryptFreshSecrets(config, config);
        Instant nextSyncAt = scheduler ? now.plus(interval, ChronoUnit.MINUTES) : null;

        ScmConnection connection = repository.save(new ScmConnection(
                UUID.randomUUID(),
                tenantId,
                req.provider(),
                config,
                scheduler,
                interval,
                nextSyncAt,
                null,
                null,
                null,
                webhook,
                now, now, null
        ));
        syncCommandProducer.publish(connection);
        return connection;
    }

    @Transactional
    public ScmConnection update(UUID tenantId, UUID id, ScmConnectionRequest req) {
        ScmConnection existing = get(tenantId, id);

        boolean scheduler = req.syncScheduler() != null ? req.syncScheduler() : existing.syncScheduler();
        int interval = req.pollIntervalMinutes() != null ? req.pollIntervalMinutes() : existing.pollIntervalMinutes();
        boolean webhook = req.webhookEnabled() != null ? req.webhookEnabled() : existing.webhookEnabled();
        String provider = req.provider() != null ? req.provider() : existing.provider();
        String config = req.config() != null ? preserveSecrets(req.config(), existing.config()) : existing.config();
        boolean configChanged = !config.equals(existing.config());

        if (webhook && !hasWebhookSecret(config)) {
            throw new IllegalArgumentException("webhookSecret is required in config when webhookEnabled is true");
        }

        // Encrypt after the presence check above so hasWebhookSecret validates the plaintext
        // value the caller actually supplied, not ciphertext. Only keys the caller freshly
        // supplied in req.config() are encrypted here — keys preserveSecrets copied over from
        // existing.config() are already ciphertext and must not be re-encrypted.
        if (req.config() != null) {
            config = encryptFreshSecrets(config, req.config());
        }

        boolean changed = !provider.equals(existing.provider())
                || configChanged
                || scheduler != existing.syncScheduler()
                || interval != existing.pollIntervalMinutes()
                || webhook != existing.webhookEnabled();

        if (!changed) {
            return existing;
        }

        // Schedule the first tick when scheduling is newly enabled; clear it when disabled.
        Instant nextSyncAt = existing.nextSyncAt();
        if (scheduler && nextSyncAt == null) {
            nextSyncAt = Instant.now().plus(interval, ChronoUnit.MINUTES);
        } else if (!scheduler) {
            nextSyncAt = null;
        }

        ScmConnection updated = repository.save(new ScmConnection(
                existing.id(),
                existing.tenantId(),
                provider,
                config,
                scheduler,
                interval,
                nextSyncAt,
                existing.lastSyncAt(),
                existing.lastSyncStatus(),
                existing.lastSyncError(),
                webhook,
                existing.createdAt(),
                Instant.now(),
                null
        ));

        // Config changes (e.g. a rotated token) fix the reason a sync was failing —
        // don't make the user wait for the next scheduled tick to find out.
        if (configChanged) {
            syncCommandProducer.publish(updated);
        }
        return updated;
    }

    /** Re-injects any secret keys the request omitted, so a config-only edit (e.g. poll interval) doesn't wipe stored credentials. */
    private static String preserveSecrets(String incomingConfig, String existingConfig) {
        try {
            Map<String, Object> incoming = new LinkedHashMap<>(MAPPER.readValue(incomingConfig, CONFIG_MAP_TYPE));
            Map<String, Object> existing = MAPPER.readValue(existingConfig, CONFIG_MAP_TYPE);
            for (String key : SECRET_KEYS) {
                if (!incoming.containsKey(key) && existing.containsKey(key)) {
                    incoming.put(key, existing.get(key));
                }
            }
            return MAPPER.writeValueAsString(incoming);
        } catch (JacksonException _) {
            return incomingConfig;
        }
    }

    /**
     * Encrypts SECRET_KEYS values in {@code config} that were freshly supplied by the caller in
     * {@code rawIncoming} (the request's own config JSON, pre-preserveSecrets). Values preserveSecrets
     * copied over from a stored connection are already ciphertext and are left untouched — only keys
     * present in rawIncoming are (re-)encrypted, so an already-encrypted preserved value is never
     * double-encrypted.
     *
     * <p>Only malformed-JSON failures are swallowed (config falls back to the caller's raw JSON, same
     * as before encryption existed). A {@code credentialEncryptor.encrypt(...)} failure — e.g. a
     * missing/malformed key — is NOT swallowed here: it must propagate and fail the write, not
     * silently persist the secret in plaintext.
     */
    private String encryptFreshSecrets(String config, String rawIncoming) {
        Map<String, Object> parsed;
        Map<String, Object> incoming;
        try {
            parsed = new LinkedHashMap<>(MAPPER.readValue(config, CONFIG_MAP_TYPE));
            incoming = MAPPER.readValue(rawIncoming, CONFIG_MAP_TYPE);
        } catch (JacksonException _) {
            return config;
        }
        for (String key : SECRET_KEYS) {
            if (incoming.containsKey(key) && parsed.get(key) instanceof String s && !s.isBlank()) {
                parsed.put(key, credentialEncryptor.encrypt(s));
            }
        }
        return MAPPER.writeValueAsString(parsed);
    }

    /** Returns true if the given config JSON contains a non-blank "webhookSecret" value. */
    private static boolean hasWebhookSecret(String config) {
        try {
            Map<String, Object> parsed = MAPPER.readValue(config, CONFIG_MAP_TYPE);
            Object secret = parsed.get("webhookSecret");
            return secret instanceof String s && !s.isBlank();
        } catch (Exception _) {
            return false;
        }
    }

    public ScmConnection get(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id)
                .orElseThrow(() -> new ScmConnectionNotFoundException(id));
    }

    public PageResult<ScmConnection> list(UUID tenantId, int limit, int offset) {
        return PageResult.of(repository.findAll(tenantId, limit, offset), repository.count(tenantId), limit, offset);
    }

    @Transactional
    public void delete(UUID tenantId, UUID id) {
        repository.softDelete(tenantId, id);
    }

    /** Publish a sync command for an existing connection (on-demand trigger). */
    public void triggerSync(UUID tenantId, UUID id) {
        syncCommandProducer.publish(get(tenantId, id));
    }
}
