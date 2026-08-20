package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.topology.domain.Dependency;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyType;
import io.cartogra.topology.repository.AdvisoryLockRepository;
import io.cartogra.topology.repository.DependencyGraphViewRepository;
import io.cartogra.topology.repository.DependencyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@code dependency_graph_edges} refresh path: the {@code REFRESH MATERIALIZED
 * VIEW CONCURRENTLY} itself, and the {@link AdvisoryLockRepository} contention it relies on
 * to keep two horizontally-scaled instances from refreshing at once (see
 * {@code DependencyGraphViewRefreshScheduler}).
 */
class DependencyGraphViewRefreshIT extends AbstractTopologyIT {

    @Autowired
    private DependencyRepository dependencyRepository;

    @Autowired
    private DependencyGraphViewRepository graphViewRepository;

    @Autowired
    private AdvisoryLockRepository lockRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void refreshPicksUpNewEdgesIntoTheMaterializedView() {
        UUID tenant = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Instant now = Instant.now();
        dependencyRepository.save(new Dependency(UUID.randomUUID(), tenant, source, target,
                DependencyType.DECLARED, DependencyProtocol.HTTP, null, now, now, null));

        graphViewRepository.refresh();

        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM dependency_graph_edges
                WHERE tenant_id = ? AND source_service_id = ? AND target_service_id = ?
                """, Integer.class, tenant, source, target);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void refreshDropsSoftDeletedEdgesFromTheMaterializedView() {
        UUID tenant = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Instant now = Instant.now();
        Dependency saved = dependencyRepository.save(new Dependency(UUID.randomUUID(), tenant, source, target,
                DependencyType.DECLARED, DependencyProtocol.HTTP, null, now, now, null));
        graphViewRepository.refresh();

        dependencyRepository.softDelete(tenant, saved.id());
        graphViewRepository.refresh();

        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM dependency_graph_edges
                WHERE tenant_id = ? AND source_service_id = ? AND target_service_id = ?
                """, Integer.class, tenant, source, target);
        assertThat(count).isZero();
    }

    @Test
    void secondCallerCannotAcquireALockAlreadyHeldByTheFirst() throws Exception {
        long key = AdvisoryLockRepository.lockKey("test-graph-view-refresh", UUID.randomUUID());
        // pg_try_advisory_lock/unlock are session (connection) scoped, so each caller needs a
        // pinned thread — a single-thread executor gives us one connection per "caller".
        ExecutorService holder = Executors.newSingleThreadExecutor();
        ExecutorService contender = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> firstAcquire = holder.submit(() -> lockRepository.tryAcquireLock(key));
            assertThat(firstAcquire.get(5, TimeUnit.SECONDS))
                    .as("first caller must acquire the free lock")
                    .isTrue();

            Future<Boolean> contendedAcquire = contender.submit(() -> lockRepository.tryAcquireLock(key));
            assertThat(contendedAcquire.get(5, TimeUnit.SECONDS))
                    .as("second caller must not acquire a lock already held by another session")
                    .isFalse();

            holder.submit(() -> {
                lockRepository.releaseLock(key);
                return null;
            }).get(5, TimeUnit.SECONDS);

            Future<Boolean> acquireAfterRelease = contender.submit(() -> lockRepository.tryAcquireLock(key));
            assertThat(acquireAfterRelease.get(5, TimeUnit.SECONDS))
                    .as("lock must become acquirable again once the holder releases it")
                    .isTrue();

            contender.submit(() -> {
                lockRepository.releaseLock(key);
                return null;
            }).get(5, TimeUnit.SECONDS);
        } finally {
            holder.shutdown();
            contender.shutdown();
        }
    }
}
