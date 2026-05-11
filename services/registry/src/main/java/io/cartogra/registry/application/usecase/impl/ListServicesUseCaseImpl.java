package io.cartogra.registry.application.usecase.impl;

import io.cartogra.common.api.PageResult;
import io.cartogra.registry.application.dto.ServiceFilter;
import io.cartogra.registry.application.repository.ServiceRepository;
import io.cartogra.registry.application.usecase.ListServicesUseCase;
import io.cartogra.registry.domain.Service;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class ListServicesUseCaseImpl implements ListServicesUseCase {

    private final ServiceRepository serviceRepository;

    public ListServicesUseCaseImpl(ServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    @Override
    public PageResult<Service> execute(UUID tenantId, ServiceFilter filter, int limit, int offset) {
        List<Service> items = serviceRepository.findAll(tenantId, filter, limit, offset);
        long total = serviceRepository.count(tenantId, filter);
        return PageResult.of(items, total, limit, offset);
    }
}
