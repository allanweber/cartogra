package io.cartogra.registry.application.usecase.impl;

import io.cartogra.common.api.PageResult;
import io.cartogra.registry.application.repository.ServiceRepository;
import io.cartogra.registry.application.usecase.DetectOrphansUseCase;
import io.cartogra.registry.domain.Service;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class DetectOrphansUseCaseImpl implements DetectOrphansUseCase {

    private final ServiceRepository serviceRepository;

    public DetectOrphansUseCaseImpl(ServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    @Override
    public PageResult<Service> execute(UUID tenantId, int limit, int offset) {
        List<Service> orphans = serviceRepository.findOrphaned(tenantId, limit, offset);
        return PageResult.of(orphans, orphans.size(), limit, offset);
    }
}
