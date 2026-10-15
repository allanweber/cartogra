package io.cartogra.topology.infrastructure.scheduled;

import io.cartogra.topology.repository.PendingSpanLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class PendingSpanLinkReaperScheduler {

    private static final Logger log = LoggerFactory.getLogger(PendingSpanLinkReaperScheduler.class);

    private final PendingSpanLinkRepository pendingSpanLinkRepository;

    public PendingSpanLinkReaperScheduler(PendingSpanLinkRepository pendingSpanLinkRepository) {
        this.pendingSpanLinkRepository = pendingSpanLinkRepository;
    }

    @Scheduled(fixedDelayString = "${topology.span-links.reap-interval:PT1M}")
    public void reap() {
        int deleted = pendingSpanLinkRepository.deleteExpired(Instant.now());
        if (deleted > 0) {
            log.info("Reaped {} expired pending_span_links rows", deleted);
        }
    }
}
