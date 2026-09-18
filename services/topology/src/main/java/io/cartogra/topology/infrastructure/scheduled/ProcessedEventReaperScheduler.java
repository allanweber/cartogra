package io.cartogra.topology.infrastructure.scheduled;

import io.cartogra.topology.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class ProcessedEventReaperScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventReaperScheduler.class);

    private final ProcessedEventRepository processedEventRepository;
    private final Duration retention;

    public ProcessedEventReaperScheduler(
            ProcessedEventRepository processedEventRepository,
            @Value("${topology.processed-events.retention:P30D}") Duration retention) {
        this.processedEventRepository = processedEventRepository;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${topology.processed-events.reaper-interval:PT1H}")
    public void reap() {
        Instant threshold = Instant.now().minus(retention);
        int deleted = processedEventRepository.deleteOlderThan(threshold);
        if (deleted > 0) {
            log.info("Reaped {} processed_events rows older than {}", deleted, threshold);
        }
    }
}
