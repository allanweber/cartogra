package io.cartogra.registry.application.usecase.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.cartogra.registry.application.dto.AssignOwnerCommand;
import io.cartogra.registry.application.repository.ServiceHistoryRepository;
import io.cartogra.registry.application.repository.ServiceRepository;
import io.cartogra.registry.application.repository.TeamRepository;
import io.cartogra.registry.application.usecase.AssignOwnerUseCase;
import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.ServiceSnapshot;
import io.cartogra.registry.domain.exception.ServiceNotFoundException;
import io.cartogra.registry.domain.exception.TeamNotFoundException;
import io.cartogra.registry.infrastructure.kafka.ServiceLifecycleEventProducer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class AssignOwnerUseCaseImpl implements AssignOwnerUseCase {

    private final ServiceRepository serviceRepository;
    private final ServiceHistoryRepository historyRepository;
    private final TeamRepository teamRepository;
    private final ObjectMapper objectMapper;
    private final ServiceLifecycleEventProducer eventProducer;

    public AssignOwnerUseCaseImpl(ServiceRepository serviceRepository,
                                  ServiceHistoryRepository historyRepository,
                                  TeamRepository teamRepository,
                                  ObjectMapper objectMapper,
                                  ServiceLifecycleEventProducer eventProducer) {
        this.serviceRepository = serviceRepository;
        this.historyRepository = historyRepository;
        this.teamRepository = teamRepository;
        this.objectMapper = objectMapper;
        this.eventProducer = eventProducer;
    }

    @Override
    @Transactional
    public Service execute(AssignOwnerCommand command) {
        Service existing = serviceRepository.findById(command.tenantId(), command.serviceId())
                .orElseThrow(() -> new ServiceNotFoundException(command.serviceId()));

        if (command.teamId() != null) {
            teamRepository.findById(command.tenantId(), command.teamId())
                    .orElseThrow(() -> new TeamNotFoundException(command.teamId()));
        }

        var updated = new Service(
                existing.id(), existing.tenantId(), existing.name(), existing.description(),
                command.teamId(),
                existing.repositoryUrl(), existing.techStack(), existing.metadata(),
                existing.healthStatus(), existing.lastDeployedAt(), existing.createdAt(),
                Instant.now(), null,
                existing.externalId(), existing.connectionId(), existing.source(), existing.repositoryRef(),
                existing.k8sCluster(), existing.k8sNamespace(), existing.k8sDeployment(),
                existing.healthEndpoint(), existing.lastCommitAt(), existing.lastCommitSha()
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
