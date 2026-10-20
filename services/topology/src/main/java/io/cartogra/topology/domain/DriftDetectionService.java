package io.cartogra.topology.domain;

import io.cartogra.topology.config.DriftProperties;
import io.cartogra.topology.repository.DependencyDriftRepository;
import io.cartogra.topology.repository.DependencyRepository;
import io.cartogra.topology.repository.EdgePairStat;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DriftDetectionService {

    private static final Logger log = LoggerFactory.getLogger(DriftDetectionService.class);

    private record Pair(UUID source, UUID target) {}

    private record DriftKey(Pair pair, DriftType type) {}

    private final DependencyRepository dependencyRepository;
    private final DependencyDriftRepository driftRepository;
    private final DriftProperties properties;
    private final MeterRegistry meterRegistry;

    public DriftDetectionService(DependencyRepository dependencyRepository,
                                 DependencyDriftRepository driftRepository,
                                 DriftProperties properties,
                                 MeterRegistry meterRegistry) {
        this.dependencyRepository = dependencyRepository;
        this.driftRepository = driftRepository;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
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

    @Transactional
    public void sweep(UUID tenantId, Instant now) {
        Instant cutoff = now.minus(properties.missingAfter());
        Map<Pair, EdgePairStat> declared = byPair(dependencyRepository.findLivePairs(tenantId, DependencyType.DECLARED));
        Map<Pair, EdgePairStat> observed = byPair(dependencyRepository.findLivePairs(tenantId, DependencyType.OBSERVED));

        Set<DriftKey> wanted = new HashSet<>();
        observed.keySet().stream()
                .filter(pair -> !declared.containsKey(pair))
                .forEach(pair -> wanted.add(new DriftKey(pair, DriftType.UNDECLARED)));
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
