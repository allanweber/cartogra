package io.cartogra.ingestion.infrastructure.scheduled;

import io.cartogra.ingestion.application.port.out.SyncJobRepository;
import io.cartogra.ingestion.infrastructure.kafka.SyncResultProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class StaleJobReaperScheduler {

    private static final Logger log = LoggerFactory.getLogger(StaleJobReaperScheduler.class);

    private final SyncJobRepository syncJobRepository;
    private final SyncResultProducer resultProducer;
    private final Duration staleTimeout;

    public StaleJobReaperScheduler(
            SyncJobRepository syncJobRepository,
            SyncResultProducer resultProducer,
            @Value("${ingestion.sync.stale-timeout:PT30M}") Duration staleTimeout) {
        this.syncJobRepository = syncJobRepository;
        this.resultProducer = resultProducer;
        this.staleTimeout = staleTimeout;
    }

    @Scheduled(fixedDelayString = "${ingestion.sync.reaper-interval:PT1M}")
    public void reap() {
        var threshold = Instant.now().minus(staleTimeout);
        var staleJobs = syncJobRepository.findStaleRunning(threshold);
        for (var job : staleJobs) {
            String reason = "reaper: timed out";
            syncJobRepository.markFailed(job.id(), reason);
            resultProducer.publishFailure(job, reason);
            log.warn("Reaped stale RUNNING job id={} connection={} tenant={}", job.id(), job.connectionId(), job.tenantId());
        }
    }
}
