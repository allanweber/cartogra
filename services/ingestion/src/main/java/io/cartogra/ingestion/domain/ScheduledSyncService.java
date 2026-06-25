package io.cartogra.ingestion.domain;

import io.cartogra.ingestion.repository.AdvisoryLockRepository;
import io.cartogra.ingestion.infrastructure.kafka.SyncCommandProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Per-connection unit of scheduled work. Runs in its own transaction so a failure
 * on one connection cannot roll back the whole tick. An advisory lock makes the
 * publish safe across multiple ingestion instances (only one wins the lock).
 */
@Service
public class ScheduledSyncService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledSyncService.class);

    private final AdvisoryLockRepository advisoryLockRepository;
    private final SyncCommandProducer syncCommandProducer;

    public ScheduledSyncService(AdvisoryLockRepository advisoryLockRepository,
                                SyncCommandProducer syncCommandProducer) {
        this.advisoryLockRepository = advisoryLockRepository;
        this.syncCommandProducer = syncCommandProducer;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attemptForConnection(ScmConnection conn) {
        if (!advisoryLockRepository.tryAcquireXactLock(lockKey(conn.id()))) {
            log.debug("Scheduler: advisory lock not acquired for connection={} — another instance is handling it", conn.id());
            return;
        }
        syncCommandProducer.publish(conn);
    }

    static long lockKey(UUID id) {
        return id.getMostSignificantBits();
    }
}
