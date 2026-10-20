package io.cartogra.topology.infrastructure.scheduled;

import io.cartogra.topology.domain.DriftDetectionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class DriftSweepScheduler {

    private final DriftDetectionService driftDetectionService;

    public DriftSweepScheduler(DriftDetectionService driftDetectionService) {
        this.driftDetectionService = driftDetectionService;
    }

    @Scheduled(fixedDelayString = "${topology.drift.sweep-interval:PT5M}",
            initialDelayString = "${topology.drift.sweep-interval:PT5M}")
    public void sweep() {
        driftDetectionService.sweepAll(Instant.now());
    }
}
