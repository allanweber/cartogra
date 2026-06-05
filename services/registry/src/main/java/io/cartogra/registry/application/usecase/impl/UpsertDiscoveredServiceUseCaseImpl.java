package io.cartogra.registry.application.usecase.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.cartogra.registry.application.dto.ServiceDiscoveryCommand;
import io.cartogra.registry.application.port.HealthEndpointValidator;
import io.cartogra.registry.application.repository.ServiceHistoryRepository;
import io.cartogra.registry.application.repository.ServiceRepository;
import io.cartogra.registry.application.usecase.UpsertDiscoveredServiceUseCase;
import io.cartogra.common.identity.SystemActors;
import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.ServiceSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class UpsertDiscoveredServiceUseCaseImpl implements UpsertDiscoveredServiceUseCase {

    private final ServiceRepository serviceRepository;
    private final ServiceHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;
    private final HealthEndpointValidator healthEndpointValidator;

    public UpsertDiscoveredServiceUseCaseImpl(ServiceRepository serviceRepository,
                                               ServiceHistoryRepository historyRepository,
                                               ObjectMapper objectMapper,
                                               HealthEndpointValidator healthEndpointValidator) {
        this.serviceRepository = serviceRepository;
        this.historyRepository = historyRepository;
        this.objectMapper = objectMapper;
        this.healthEndpointValidator = healthEndpointValidator;
    }

    @Override
    @Transactional
    public Service execute(ServiceDiscoveryCommand command) {
        if (command.healthEndpoint() != null) {
            healthEndpointValidator.validate(command.healthEndpoint());
        }
        Service saved = serviceRepository.upsertDiscovered(command);
        historyRepository.save(toSnapshot(saved));
        return saved;
    }

    private ServiceSnapshot toSnapshot(Service service) {
        try {
            String json = objectMapper.writeValueAsString(service);
            return new ServiceSnapshot(UUID.randomUUID(), service.id(), service.tenantId(), json,
                    SystemActors.SYSTEM, Instant.now());
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize service snapshot", e);
        }
    }
}
