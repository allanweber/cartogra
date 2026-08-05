package io.cartogra.topology.infrastructure.scheduled;

import io.cartogra.topology.repository.AdvisoryLockRepository;
import io.cartogra.topology.repository.DependencyGraphViewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Debounces {@code dependency_graph_edges} refreshes: mutations call {@link #markDirty()}
 * instead of refreshing inline, and a fixed-delay tick coalesces any writes since the last
 * refresh into a single {@code REFRESH MATERIALIZED VIEW CONCURRENTLY}. The advisory lock
 * ensures only one instance refreshes at a time if the service is scaled horizontally.
 */
@Component
public class DependencyGraphViewRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(DependencyGraphViewRefreshScheduler.class);
    private static final long LOCK_KEY = AdvisoryLockRepository.lockKey("dependency-graph-view", "refresh");

    private final DependencyGraphViewRepository graphViewRepository;
    private final AdvisoryLockRepository lockRepository;
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public DependencyGraphViewRefreshScheduler(DependencyGraphViewRepository graphViewRepository,
            AdvisoryLockRepository lockRepository) {
        this.graphViewRepository = graphViewRepository;
        this.lockRepository = lockRepository;
    }

    public void markDirty() {
        dirty.set(true);
    }

    @Scheduled(fixedDelayString = "${topology.graph-view.refresh-interval:PT5S}")
    public void tick() {
        if (!dirty.get()) {
            return;
        }
        if (!lockRepository.tryAcquireLock(LOCK_KEY)) {
            return;
        }
        try {
            dirty.set(false);
            graphViewRepository.refresh();
        } catch (Exception e) {
            dirty.set(true);
            log.error("Failed to refresh dependency_graph_edges", e);
        } finally {
            lockRepository.releaseLock(LOCK_KEY);
        }
    }
}
