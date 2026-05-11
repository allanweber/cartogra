package io.cartogra.registry.application.usecase.impl;

import io.cartogra.registry.application.repository.ServiceRepository;
import io.cartogra.registry.application.usecase.FindServiceUseCase;
import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.exception.ServiceNotFoundException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class FindServiceUseCaseImpl implements FindServiceUseCase {

    private final ServiceRepository serviceRepository;

    public FindServiceUseCaseImpl(ServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    @Override
    public Service execute(UUID tenantId, UUID serviceId) {
        return serviceRepository.findById(tenantId, serviceId)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));
    }
}
