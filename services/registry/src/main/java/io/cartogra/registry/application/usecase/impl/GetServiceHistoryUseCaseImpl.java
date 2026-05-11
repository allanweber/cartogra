package io.cartogra.registry.application.usecase.impl;

import io.cartogra.common.api.PageResult;
import io.cartogra.registry.application.repository.ServiceHistoryRepository;
import io.cartogra.registry.application.repository.ServiceRepository;
import io.cartogra.registry.application.usecase.GetServiceHistoryUseCase;
import io.cartogra.registry.domain.ServiceSnapshot;
import io.cartogra.registry.domain.exception.ServiceNotFoundException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class GetServiceHistoryUseCaseImpl implements GetServiceHistoryUseCase {

    private final ServiceHistoryRepository historyRepository;
    private final ServiceRepository serviceRepository;

    public GetServiceHistoryUseCaseImpl(ServiceHistoryRepository historyRepository,
                                        ServiceRepository serviceRepository) {
        this.historyRepository = historyRepository;
        this.serviceRepository = serviceRepository;
    }

    @Override
    public PageResult<ServiceSnapshot> execute(UUID tenantId, UUID serviceId, int limit, int offset) {
        serviceRepository.findById(tenantId, serviceId)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));
        List<ServiceSnapshot> items = historyRepository.findByServiceId(tenantId, serviceId, limit, offset);
        return PageResult.of(items, items.size(), limit, offset);
    }

    @Override
    public Optional<ServiceSnapshot> atPointInTime(UUID tenantId, UUID serviceId, Instant at) {
        serviceRepository.findById(tenantId, serviceId)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));
        return historyRepository.findAtPointInTime(tenantId, serviceId, at);
    }
}
