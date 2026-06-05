package io.cartogra.registry.application.usecase.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.cartogra.registry.application.dto.UpdateServiceCommand;
import io.cartogra.registry.application.port.HealthEndpointValidator;
import io.cartogra.registry.application.repository.ServiceHistoryRepository;
import io.cartogra.registry.application.repository.ServiceRepository;
import io.cartogra.registry.application.usecase.UpdateServiceUseCase;
import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.ServiceHealthStatus;
import io.cartogra.registry.domain.ServiceSnapshot;
import io.cartogra.registry.domain.exception.DuplicateServiceNameException;
import io.cartogra.registry.domain.exception.ServiceNotFoundException;
import io.cartogra.registry.infrastructure.kafka.ServiceLifecycleEventProducer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class UpdateServiceUseCaseImpl implements UpdateServiceUseCase {

    private final ServiceRepository serviceRepository;
    private final ServiceHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;
    private final ServiceLifecycleEventProducer eventProducer;
    private final HealthEndpointValidator healthEndpointValidator;

    public UpdateServiceUseCaseImpl(ServiceRepository serviceRepository,
                                    ServiceHistoryRepository historyRepository,
                                    ObjectMapper objectMapper,
                                    ServiceLifecycleEventProducer eventProducer,
                                    HealthEndpointValidator healthEndpointValidator) {
        this.serviceRepository = serviceRepository;
        this.historyRepository = historyRepository;
        this.objectMapper = objectMapper;
        this.eventProducer = eventProducer;
        this.healthEndpointValidator = healthEndpointValidator;
    }

    @Override
    @Transactional
    public Service execute(UpdateServiceCommand command) {
        Service existing = serviceRepository.findById(command.tenantId(), command.serviceId())
                .orElseThrow(() -> new ServiceNotFoundException(command.serviceId()));

        if (!existing.name().equalsIgnoreCase(command.name())
                && serviceRepository.existsByName(command.tenantId(), command.name(), command.serviceId())) {
            throw new DuplicateServiceNameException(command.name());
        }
        if (command.healthEndpoint() != null) {
            healthEndpointValidator.validate(command.healthEndpoint());
        }

        var updated = new Service(
                existing.id(),
                existing.tenantId(),
                command.name() != null ? command.name() : existing.name(),
                command.description() != null ? command.description() : existing.description(),
                existing.teamId(),
                command.repositoryUrl() != null ? command.repositoryUrl() : existing.repositoryUrl(),
                command.techStack() != null ? command.techStack() : existing.techStack(),
                command.metadata() != null ? command.metadata() : existing.metadata(),
                command.healthStatus() != null ? ServiceHealthStatus.fromString(command.healthStatus()) : existing.healthStatus(),
                existing.lastDeployedAt(),
                existing.createdAt(),
                Instant.now(),
                null,
                existing.externalId(),
                existing.connectionId(),
                existing.source(),
                existing.repositoryRef(),
                existing.k8sCluster(),
                existing.k8sNamespace(),
                existing.k8sDeployment(),
                command.healthEndpoint() != null ? command.healthEndpoint() : existing.healthEndpoint(),
                existing.lastCommitAt(),
                existing.lastCommitSha(),
                existing.healthCheckedAt()
        );

        Service saved = serviceRepository.save(updated);
        historyRepository.save(toSnapshot(saved, command.requestedBy()));
        eventProducer.publishUpdated(saved);
        return saved;
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
