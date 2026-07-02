package io.cartogra.gateway.domain;

import io.cartogra.gateway.domain.exception.NotFoundException;
import io.cartogra.gateway.repository.TenantRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TenantService {

    private final TenantRepository repository;

    public TenantService(TenantRepository repository) {
        this.repository = repository;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public Tenant getTenant(UUID tenantId) {
        return repository.findByTenantId(tenantId)
            .orElseThrow(() -> new NotFoundException("Tenant not found"));
    }
}
