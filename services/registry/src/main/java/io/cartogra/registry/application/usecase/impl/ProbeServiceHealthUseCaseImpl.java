package io.cartogra.registry.application.usecase.impl;

import io.cartogra.common.identity.SystemActors;
import io.cartogra.registry.application.port.ServiceHealthChecker;
import io.cartogra.registry.application.repository.AdvisoryLockRepository;
import io.cartogra.registry.application.repository.ServiceHistoryRepository;
import io.cartogra.registry.application.repository.ServiceRepository;
import io.cartogra.registry.application.usecase.ProbeServiceHealthUseCase;
import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.ServiceHealthStatus;
import io.cartogra.registry.domain.ServiceSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class ProbeServiceHealthUseCaseImpl implements ProbeServiceHealthUseCase {

    private static final Logger logger = LoggerFactory.getLogger(ProbeServiceHealthUseCaseImpl.class);

    // hashtext('cartogra.registry.health-probe') as unsigned long — stable across JVM restarts
    private static final long HEALTH_PROBE_LOCK_KEY =
            0xFFFFFFFFL & "cartogra.registry.health-probe".hashCode();

    private final ServiceRepository serviceRepository;
    private final ServiceHistoryRepository historyRepository;
    private final ServiceHealthChecker healthChecker;
    private final AdvisoryLockRepository advisoryLockRepository;
    private final ObjectMapper objectMapper;

    public ProbeServiceHealthUseCaseImpl(ServiceRepository serviceRepository,
                                         ServiceHistoryRepository historyRepository,
                                         ServiceHealthChecker healthChecker,
                                         AdvisoryLockRepository advisoryLockRepository,
                                         ObjectMapper objectMapper) {
        this.serviceRepository = serviceRepository;
        this.historyRepository = historyRepository;
        this.healthChecker = healthChecker;
        this.advisoryLockRepository = advisoryLockRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void probeAll() {
        if (!advisoryLockRepository.tryAcquireLock(HEALTH_PROBE_LOCK_KEY)) {
            logger.debug("Health probe advisory lock already held by another replica; skipping this round");
            return;
        }
        try {
            List<Service> services = serviceRepository.findAllWithHealthEndpoint();
            for (Service service : services) {
                probeOne(service);
            }
        } finally {
            advisoryLockRepository.releaseLock(HEALTH_PROBE_LOCK_KEY);
        }
    }

    private void probeOne(Service service) {
        try {
            ServiceHealthStatus newStatus = healthChecker.check(service.healthEndpoint());
            Instant checkedAt = Instant.now();
            serviceRepository.updateHealth(service.tenantId(), service.id(), newStatus, checkedAt);
            if (newStatus != service.healthStatus()) {
                historyRepository.save(toSnapshot(service, newStatus, checkedAt));
            }
        } catch (Exception e) {
            logger.warn("Unexpected error probing service {} (tenant {}): {}",
                    service.id(), service.tenantId(), e.getMessage());
            serviceRepository.updateHealth(service.tenantId(), service.id(),
                    ServiceHealthStatus.UNHEALTHY, Instant.now());
        }
    }

    private ServiceSnapshot toSnapshot(Service service, ServiceHealthStatus newStatus, Instant checkedAt) {
        var withNewStatus = new Service(
                service.id(), service.tenantId(), service.name(), service.description(),
                service.teamId(), service.repositoryUrl(), service.techStack(), service.metadata(),
                newStatus, service.lastDeployedAt(), service.createdAt(), service.updatedAt(),
                service.deletedAt(), service.externalId(), service.connectionId(), service.source(),
                service.repositoryRef(), service.k8sCluster(), service.k8sNamespace(),
                service.k8sDeployment(), service.healthEndpoint(), service.lastCommitAt(),
                service.lastCommitSha(), checkedAt
        );
        try {
            String json = objectMapper.writeValueAsString(withNewStatus);
            return new ServiceSnapshot(UUID.randomUUID(), service.id(), service.tenantId(),
                    json, SystemActors.SYSTEM, checkedAt);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize health probe snapshot", e);
        }
    }
}
