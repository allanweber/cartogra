package io.cartogra.topology.infrastructure.scheduled;

import io.cartogra.topology.domain.DriftService;
import io.cartogra.web.lock.AdvisoryLockRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Only one instance sweeps at a time: concurrent REPEATABLE READ sweeps of one tenant would fail each other's writes. */
@Component
public class DriftSweepScheduler {

    private static final long LOCK_KEY = AdvisoryLockRepository.lockKey("dependency-drift", "sweep");

    private final DriftService driftService;
    private final AdvisoryLockRepository lockRepository;

    public DriftSweepScheduler(DriftService driftService, AdvisoryLockRepository lockRepository) {
        this.driftService = driftService;
        this.lockRepository = lockRepository;
    }

    @Scheduled(fixedDelayString = "${topology.drift.sweep-interval:PT5M}",
            initialDelayString = "${topology.drift.sweep-interval:PT5M}")
    public void sweep() {
        if (!lockRepository.tryAcquireLock(LOCK_KEY)) {
            return;
        }
        try {
            driftService.sweepAll(Instant.now());
        } finally {
            lockRepository.releaseLock(LOCK_KEY);
        }
    }
}
