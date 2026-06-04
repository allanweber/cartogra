package io.cartogra.registry.application.usecase.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.cartogra.registry.application.repository.ServiceHistoryRepository;
import io.cartogra.registry.application.repository.ServiceRepository;
import io.cartogra.registry.application.usecase.DeleteServiceUseCase;
import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.ServiceSnapshot;
import io.cartogra.registry.domain.exception.ServiceNotFoundException;
import io.cartogra.registry.infrastructure.kafka.ServiceLifecycleEventProducer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class DeleteServiceUseCaseImpl implements DeleteServiceUseCase {

    private final ServiceRepository serviceRepository;
    private final ServiceHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;
    private final ServiceLifecycleEventProducer eventProducer;

    public DeleteServiceUseCaseImpl(ServiceRepository serviceRepository,
                                    ServiceHistoryRepository historyRepository,
                                    ObjectMapper objectMapper,
                                    ServiceLifecycleEventProducer eventProducer) {
        this.serviceRepository = serviceRepository;
        this.historyRepository = historyRepository;
        this.objectMapper = objectMapper;
        this.eventProducer = eventProducer;
    }

    @Override
    @Transactional
    public void execute(UUID tenantId, UUID serviceId, UUID requestedBy) {
        Service existing = serviceRepository.findById(tenantId, serviceId)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));

        serviceRepository.softDelete(tenantId, serviceId);

        Instant deletedAt = Instant.now();
        var deleted = new Service(
                existing.id(), existing.tenantId(), existing.name(), existing.description(),
                existing.teamId(), existing.repositoryUrl(), existing.techStack(), existing.metadata(),
                existing.healthStatus(), existing.lastDeployedAt(), existing.createdAt(),
                deletedAt, deletedAt,
                existing.externalId(), existing.connectionId(), existing.source(), existing.repositoryRef(),
                existing.k8sCluster(), existing.k8sNamespace(), existing.k8sDeployment(),
                existing.healthEndpoint(), existing.lastCommitAt(), existing.lastCommitSha()
        );
        historyRepository.save(toSnapshot(deleted, requestedBy));
        eventProducer.publishDeleted(existing, deletedAt);
    }

    private ServiceSnapshot toSnapshot(Service service, UUID changedBy) {
        try {
            String json = objectMapper.writeValueAsString(service);
            return new ServiceSnapshot(UUID.randomUUID(), service.id(), service.tenantId(), json, changedBy, Instant.now());
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize service snapshot", e);
        }
    }
}
