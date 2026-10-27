package io.cartogra.topology.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.topology.config.DriftProperties;
import io.cartogra.topology.domain.exception.DriftAlreadyResolvedException;
import io.cartogra.topology.domain.exception.DriftNoLongerObservedException;
import io.cartogra.topology.domain.exception.DriftNotFoundException;
import io.cartogra.topology.domain.exception.InvalidDriftActionException;
import io.cartogra.topology.repository.DependencyDriftRepository;
import io.cartogra.topology.repository.DependencyRepository;
import io.cartogra.topology.repository.EdgePairStat;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The drift lifecycle: raised by the sweep, closed by the sweep when its condition clears ({@code AUTO}), or resolved
 * by a user (accept, remove, dismiss). One open drift per pair and type; a dismissed pair is never raised again.
 * Each tenant sweep runs in its own REPEATABLE READ transaction so the declared, observed and drift reads share one
 * snapshot: a resolve committing mid-sweep fails the sweep's write instead of raising a false drift.
 */
@Service
public class DriftService {

    private static final Logger log = LoggerFactory.getLogger(DriftService.class);
    private static final int MAX_LIMIT = 200;

    private record Pair(UUID source, UUID target) {}

    private record DriftKey(Pair pair, DriftType type) {}

    private final DependencyDriftRepository driftRepository;
    private final DependencyRepository dependencyRepository;
    private final DependencyService dependencyService;
    private final DriftProperties properties;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate resolveTransaction;
    private final TransactionTemplate sweepTransaction;

    public DriftService(DependencyDriftRepository driftRepository,
                        DependencyRepository dependencyRepository,
                        DependencyService dependencyService,
                        DriftProperties properties,
                        MeterRegistry meterRegistry,
                        PlatformTransactionManager transactionManager) {
        this.driftRepository = driftRepository;
        this.dependencyRepository = dependencyRepository;
        this.dependencyService = dependencyService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.resolveTransaction = new TransactionTemplate(transactionManager);
        this.sweepTransaction = new TransactionTemplate(transactionManager);
        this.sweepTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public PageResult<DependencyDrift> list(UUID tenantId, int limit, int offset) {
        int boundedLimit = Math.clamp(limit, 1, MAX_LIMIT);
        int boundedOffset = Math.max(offset, 0);
        return PageResult.of(driftRepository.findActive(tenantId, boundedLimit, boundedOffset),
                driftRepository.countActive(tenantId), boundedLimit, boundedOffset);
    }

    public DependencyDrift resolve(UUID tenantId, @Nullable UUID userId, UUID id, DriftAction action) {
        DependencyDrift drift = driftRepository.findById(tenantId, id)
                .orElseThrow(() -> new DriftNotFoundException(id));
        dependencyService.requirePairAccess(tenantId, userId, drift.sourceServiceId(), drift.targetServiceId());
        if (drift.isResolved()) {
            throw new DriftAlreadyResolvedException(id);
        }
        if (!action.appliesTo(drift.type())) {
            throw new InvalidDriftActionException(action, drift.type());
        }

        resolveTransaction.executeWithoutResult(status -> {
            if (!driftRepository.resolve(tenantId, id, userId, action.resolution(), Instant.now())) {
                throw new DriftAlreadyResolvedException(id);
            }
            switch (action) {
                case ACCEPT_OBSERVED -> {
                    if (!dependencyService.declareObservedPair(tenantId, drift.sourceServiceId(),
                            drift.targetServiceId())) {
                        throw new DriftNoLongerObservedException(id);
                    }
                }
                case REMOVE_DECLARED -> dependencyService.removeDeclaredPair(tenantId, drift.sourceServiceId(),
                        drift.targetServiceId());
                case DISMISS -> { }
            }
        });
        return driftRepository.findById(tenantId, id).orElseThrow(() -> new DriftNotFoundException(id));
    }

    public void sweepAll(Instant now) {
        for (UUID tenantId : driftRepository.findTenantIdsToSweep()) {
            try {
                sweep(tenantId, now);
            } catch (RuntimeException e) {
                log.warn("Drift sweep failed for tenant {}", tenantId, e);
            }
        }
    }

    /**
     * Undeclared = observed since the {@code missing-after} cutoff with no declared pair; missing = declared before
     * the cutoff and not observed since, only for tenants with observed edges. Protocol is ignored.
     */
    public void sweep(UUID tenantId, Instant now) {
        sweepTransaction.executeWithoutResult(_ -> sweepInTransaction(tenantId, now));
    }

    private void sweepInTransaction(UUID tenantId, Instant now) {
        Instant cutoff = now.minus(properties.missingAfter());
        Map<Pair, EdgePairStat> declared = byPair(dependencyRepository.findLivePairs(tenantId, DependencyType.DECLARED));
        Map<Pair, EdgePairStat> observed = byPair(dependencyRepository.findLivePairs(tenantId, DependencyType.OBSERVED));

        Set<DriftKey> wanted = new HashSet<>();
        observed.forEach((pair, stat) -> {
            if (!declared.containsKey(pair) && !stat.lastActiveAt().isBefore(cutoff)) {
                wanted.add(new DriftKey(pair, DriftType.UNDECLARED));
            }
        });
        if (!observed.isEmpty()) {
            declared.forEach((pair, stat) -> {
                EdgePairStat seen = observed.get(pair);
                boolean old = stat.firstCreatedAt().isBefore(cutoff);
                boolean silent = seen == null || seen.lastActiveAt().isBefore(cutoff);
                if (old && silent) {
                    wanted.add(new DriftKey(pair, DriftType.MISSING));
                }
            });
        }

        Set<DriftKey> active = new HashSet<>();
        for (DependencyDrift drift : driftRepository.findAllActive(tenantId)) {
            DriftKey key = keyOf(drift);
            if (wanted.contains(key)) {
                active.add(key);
            } else if (driftRepository.resolve(tenantId, drift.id(), null, DriftResolution.AUTO, now)) {
                meterRegistry.counter("cartogra.drift.autoclosed", "type", drift.type().toDbValue()).increment();
            }
        }

        Set<DriftKey> dismissed = new HashSet<>();
        driftRepository.findDismissed(tenantId).forEach(drift -> dismissed.add(keyOf(drift)));

        for (DriftKey key : wanted) {
            if (active.contains(key) || dismissed.contains(key)) {
                continue;
            }
            DependencyDrift fresh = new DependencyDrift(UUID.randomUUID(), tenantId, key.pair().source(),
                    key.pair().target(), key.type(), now, null, null, null);
            if (driftRepository.insertIfAbsent(fresh)) {
                meterRegistry.counter("cartogra.drift.detected", "type", key.type().toDbValue()).increment();
            }
        }
    }

    private static DriftKey keyOf(DependencyDrift drift) {
        return new DriftKey(new Pair(drift.sourceServiceId(), drift.targetServiceId()), drift.type());
    }

    private static Map<Pair, EdgePairStat> byPair(List<EdgePairStat> stats) {
        Map<Pair, EdgePairStat> map = new HashMap<>();
        stats.forEach(stat -> map.put(new Pair(stat.sourceServiceId(), stat.targetServiceId()), stat));
        return map;
    }
}
