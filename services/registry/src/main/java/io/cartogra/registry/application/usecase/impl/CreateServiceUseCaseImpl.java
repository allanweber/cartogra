package io.cartogra.registry.application.usecase.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.cartogra.registry.application.dto.CreateServiceCommand;
import io.cartogra.registry.application.repository.ServiceHistoryRepository;
import io.cartogra.registry.application.repository.ServiceRepository;
import io.cartogra.registry.application.repository.TeamRepository;
import io.cartogra.registry.application.usecase.CreateServiceUseCase;
import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.ServiceHealthStatus;
import io.cartogra.registry.domain.ServiceSnapshot;
import io.cartogra.registry.domain.exception.DuplicateServiceNameException;
import io.cartogra.registry.domain.exception.TeamNotFoundException;
import io.cartogra.registry.infrastructure.kafka.ServiceLifecycleEventProducer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class CreateServiceUseCaseImpl implements CreateServiceUseCase {

    private final ServiceRepository serviceRepository;
    private final ServiceHistoryRepository historyRepository;
    private final TeamRepository teamRepository;
    private final ObjectMapper objectMapper;
    private final ServiceLifecycleEventProducer eventProducer;

    public CreateServiceUseCaseImpl(ServiceRepository serviceRepository,
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
    public Service execute(CreateServiceCommand command) {
        if (serviceRepository.existsByName(command.tenantId(), command.name(), null)) {
            throw new DuplicateServiceNameException(command.name());
        }
        if (command.teamId() != null) {
            teamRepository.findById(command.tenantId(), command.teamId())
                    .orElseThrow(() -> new TeamNotFoundException(command.teamId()));
        }

        Instant now = Instant.now();
        var service = new Service(
                UUID.randomUUID(),
                command.tenantId(),
                command.name(),
                command.description(),
                command.teamId(),
                command.repositoryUrl(),
                command.techStack(),
                command.metadata(),
                ServiceHealthStatus.UNKNOWN,
                null,
                now,
                now,
                null
        );

        Service saved = serviceRepository.save(service);
        historyRepository.save(toSnapshot(saved, command.requestedBy()));
        eventProducer.publishRegistered(saved);
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
