package io.cartogra.ingestion.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.ingestion.api.dto.ScmConnectionRequest;
import io.cartogra.ingestion.domain.exception.PlanLimitExceededException;
import io.cartogra.ingestion.repository.ScmConnectionRepository;
import io.cartogra.ingestion.domain.exception.ScmConnectionNotFoundException;
import io.cartogra.ingestion.infrastructure.kafka.SyncCommandProducer;
import io.cartogra.ingestion.infrastructure.registry.RegistryPlanLimitClient;
import io.cartogra.ingestion.infrastructure.registry.RegistryPlanLimits;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * SCM connection CRUD plus on-demand sync triggering. The controller talks only
 * to this service; persistence stays behind {@link ScmConnectionRepository}.
 */
@Service
public class ScmConnectionService {

    private static final int DEFAULT_POLL_INTERVAL_MINUTES = 15;

    private final ScmConnectionRepository repository;
    private final SyncCommandProducer syncCommandProducer;
    private final RegistryPlanLimitClient planLimitClient;

    public ScmConnectionService(ScmConnectionRepository repository, SyncCommandProducer syncCommandProducer,
                                RegistryPlanLimitClient planLimitClient) {
        this.repository = repository;
        this.syncCommandProducer = syncCommandProducer;
        this.planLimitClient = planLimitClient;
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
        Instant nextSyncAt = scheduler ? now.plus(interval, ChronoUnit.MINUTES) : null;

        ScmConnection connection = repository.save(new ScmConnection(
                UUID.randomUUID(),
                tenantId,
                req.provider(),
                req.config() != null ? req.config() : "{}",
                scheduler,
                interval,
                nextSyncAt,
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
        String config = req.config() != null ? req.config() : existing.config();

        boolean changed = !provider.equals(existing.provider())
                || !config.equals(existing.config())
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

        return repository.save(new ScmConnection(
                existing.id(),
                existing.tenantId(),
                provider,
                config,
                scheduler,
                interval,
                nextSyncAt,
                existing.lastSyncAt(),
                existing.lastSyncStatus(),
                webhook,
                existing.createdAt(),
                Instant.now(),
                null
        ));
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
