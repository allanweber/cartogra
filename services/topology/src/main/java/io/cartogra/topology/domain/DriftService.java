package io.cartogra.topology.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.topology.domain.exception.DriftAlreadyResolvedException;
import io.cartogra.topology.domain.exception.DriftNotFoundException;
import io.cartogra.topology.domain.exception.InvalidDriftActionException;
import io.cartogra.topology.infrastructure.scheduled.DependencyGraphViewRefreshScheduler;
import io.cartogra.topology.repository.DependencyDriftRepository;
import io.cartogra.topology.repository.DependencyRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
public class DriftService {

    private static final int MAX_LIMIT = 200;

    private final DependencyDriftRepository driftRepository;
    private final DependencyRepository dependencyRepository;
    private final DependencyService dependencyService;
    private final DependencyGraphViewRefreshScheduler graphViewRefreshScheduler;
    private final TransactionTemplate transactionTemplate;

    public DriftService(DependencyDriftRepository driftRepository,
                        DependencyRepository dependencyRepository,
                        DependencyService dependencyService,
                        DependencyGraphViewRefreshScheduler graphViewRefreshScheduler,
                        PlatformTransactionManager transactionManager) {
        this.driftRepository = driftRepository;
        this.dependencyRepository = dependencyRepository;
        this.dependencyService = dependencyService;
        this.graphViewRefreshScheduler = graphViewRefreshScheduler;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
        dependencyService.requireEitherSideAccess(tenantId, userId, drift.sourceServiceId(), drift.targetServiceId());
        if (drift.isResolved()) {
            throw new DriftAlreadyResolvedException(id);
        }
        if (!action.appliesTo(drift.type())) {
            throw new InvalidDriftActionException(action, drift.type());
        }

        transactionTemplate.executeWithoutResult(status -> {
            if (!driftRepository.resolve(tenantId, id, userId, action.resolution(), Instant.now())) {
                throw new DriftAlreadyResolvedException(id);
            }
            switch (action) {
                case ACCEPT_OBSERVED -> declareObserved(tenantId, drift);
                case REMOVE_DECLARED -> {
                    dependencyRepository.softDeleteDeclaredPair(tenantId, drift.sourceServiceId(),
                            drift.targetServiceId());
                    graphViewRefreshScheduler.markDirty();
                }
                case DISMISS -> { }
            }
        });
        return driftRepository.findById(tenantId, id).orElseThrow(() -> new DriftNotFoundException(id));
    }

    private void declareObserved(UUID tenantId, DependencyDrift drift) {
        Instant now = Instant.now();
        for (DependencyProtocol protocol : dependencyRepository.findLiveProtocols(tenantId,
                drift.sourceServiceId(), drift.targetServiceId(), DependencyType.OBSERVED)) {
            boolean declared = dependencyRepository.findByEdgeIdentity(tenantId, drift.sourceServiceId(),
                    drift.targetServiceId(), DependencyType.DECLARED, protocol).isPresent();
            if (!declared) {
                dependencyRepository.save(new Dependency(UUID.randomUUID(), tenantId, drift.sourceServiceId(),
                        drift.targetServiceId(), DependencyType.DECLARED, protocol, null, now, now, null));
            }
        }
        graphViewRefreshScheduler.markDirty();
    }
}
