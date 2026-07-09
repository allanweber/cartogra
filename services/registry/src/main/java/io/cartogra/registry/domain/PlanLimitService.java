package io.cartogra.registry.domain;

import io.cartogra.registry.repository.PlanLimitRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PlanLimitService {

    private final PlanLimitRepository repository;

    public PlanLimitService(PlanLimitRepository repository) {
        this.repository = repository;
    }

    public PlanLimits getLimits(UUID tenantId) {
        return repository.findByTenantId(tenantId);
    }
}
