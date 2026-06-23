package io.cartogra.gateway.domain;

import io.cartogra.gateway.api.dto.TenantOidcConfigRequest;
import io.cartogra.gateway.repository.TenantOidcConfigRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Per-tenant OIDC provider configuration (admin-managed). */
@Service
public class TenantOidcConfigService {

    private final TenantOidcConfigRepository repository;

    public TenantOidcConfigService(TenantOidcConfigRepository repository) {
        this.repository = repository;
    }

    public TenantOidcConfig create(UUID tenantId, TenantOidcConfigRequest request) {
        Instant now = Instant.now();
        return repository.save(new TenantOidcConfig(null, tenantId,
            request.discoveryUri(), request.clientId(), request.clientSecret(),
            true, now, now, null));
    }

    public Optional<TenantOidcConfig> get(UUID id, UUID tenantId) {
        return repository.findByIdAndTenantId(id, tenantId);
    }

    public Optional<TenantOidcConfig> update(UUID id, UUID tenantId, TenantOidcConfigRequest request) {
        return repository.findByIdAndTenantId(id, tenantId)
            .map(existing -> repository.save(new TenantOidcConfig(existing.id(), existing.tenantId(),
                request.discoveryUri(), request.clientId(), request.clientSecret(),
                existing.enabled(), existing.createdAt(), Instant.now(), existing.deletedAt())));
    }

    public void delete(UUID id, UUID tenantId) {
        repository.softDeleteByIdAndTenantId(id, tenantId);
    }
}
