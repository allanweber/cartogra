package io.cartogra.gateway.domain;

import io.cartogra.gateway.domain.exception.NotFoundException;
import io.cartogra.gateway.repository.TenantRepository;
import io.cartogra.gateway.repository.UserRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TenantService {

    private final TenantRepository repository;
    private final UserRepository userRepository;

    public TenantService(TenantRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public Tenant getTenant(UUID tenantId) {
        return repository.findByTenantId(tenantId)
            .orElseThrow(() -> new NotFoundException("Tenant not found"));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public long countUsers(UUID tenantId) {
        return userRepository.count(tenantId);
    }
}
