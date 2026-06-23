package io.cartogra.ingestion.infrastructure.scheduled;

import io.cartogra.ingestion.repository.ScmConnectionRepository;
import io.cartogra.ingestion.domain.ScheduledSyncService;
import io.cartogra.ingestion.domain.ScmConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Periodic tick that finds due scheduled connections and triggers a sync for each.
 * The tick is not transactional: each connection is an independent boundary and the
 * timestamp advance happens outside the lock transaction, giving at-least-once delivery.
 */
@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final ScmConnectionRepository connectionRepository;
    private final ScheduledSyncService scheduledSyncService;

    public SyncScheduler(ScmConnectionRepository connectionRepository,
                         ScheduledSyncService scheduledSyncService) {
        this.connectionRepository = connectionRepository;
        this.scheduledSyncService = scheduledSyncService;
    }

    @Scheduled(fixedDelayString = "${ingestion.sync.poll-interval:PT15M}")
    public void tick() {
        for (ScmConnection conn : connectionRepository.findDue()) {
            try {
                scheduledSyncService.attemptForConnection(conn);
                Instant now = Instant.now();
                Instant next = now.plus(conn.pollIntervalMinutes(), ChronoUnit.MINUTES);
                connectionRepository.updateSyncTimestamps(conn.id(), now, next);
            } catch (Exception ex) {
                log.warn("Scheduler: failed for connection={}: {}", conn.id(), ex.getMessage());
            }
        }
    }
}
