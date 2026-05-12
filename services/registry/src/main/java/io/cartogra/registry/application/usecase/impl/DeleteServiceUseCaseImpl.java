package io.cartogra.registry.application.usecase.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.cartogra.registry.application.repository.ServiceHistoryRepository;
import io.cartogra.registry.application.repository.ServiceRepository;
import io.cartogra.registry.application.usecase.DeleteServiceUseCase;
import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.ServiceSnapshot;
import io.cartogra.registry.domain.exception.ServiceNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class DeleteServiceUseCaseImpl implements DeleteServiceUseCase {

    private final ServiceRepository serviceRepository;
    private final ServiceHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    public DeleteServiceUseCaseImpl(ServiceRepository serviceRepository,
                                    ServiceHistoryRepository historyRepository,
                                    ObjectMapper objectMapper) {
        this.serviceRepository = serviceRepository;
        this.historyRepository = historyRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void execute(UUID tenantId, UUID serviceId, UUID requestedBy) {
        Service existing = serviceRepository.findById(tenantId, serviceId)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));

        serviceRepository.softDelete(tenantId, serviceId);

        var deleted = new Service(
                existing.id(), existing.tenantId(), existing.name(), existing.description(),
                existing.teamId(), existing.repositoryUrl(), existing.techStack(), existing.metadata(),
                existing.healthStatus(), existing.lastDeployedAt(), existing.createdAt(),
                Instant.now(), Instant.now()
        );
        historyRepository.save(toSnapshot(deleted, requestedBy));
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
