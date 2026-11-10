package io.cartogra.gateway.domain;

import io.cartogra.common.audit.AuditEvent;
import io.cartogra.common.audit.AuditEventPort;
import io.cartogra.common.audit.AuditKind;
import io.cartogra.gateway.api.dto.TenantOidcConfigRequest;
import io.cartogra.gateway.repository.TenantOidcConfigRepository;
import io.cartogra.common.identity.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Per-tenant OIDC provider configuration (admin-managed). */
@Service
public class TenantOidcConfigService {

    private final TenantOidcConfigRepository repository;
    private final AuditEventPort auditEventPort;

    public TenantOidcConfigService(TenantOidcConfigRepository repository, AuditEventPort auditEventPort) {
        this.repository = repository;
        this.auditEventPort = auditEventPort;
    }

    @Transactional
    public TenantOidcConfig create(UUID tenantId, Actor actor, TenantOidcConfigRequest request) {
        Instant now = Instant.now();
        TenantOidcConfig saved = repository.save(new TenantOidcConfig(null, tenantId,
            request.discoveryUri(), request.clientId(), request.clientSecret(),
            true, now, now, null));
        audit(saved, actor, "created", Map.of("discoveryUri", saved.discoveryUri(), "clientId", saved.clientId()));
        return saved;
    }

    public Optional<TenantOidcConfig> get(UUID id, UUID tenantId) {
        return repository.findByIdAndTenantId(id, tenantId);
    }

    @Transactional
    public Optional<TenantOidcConfig> update(UUID id, UUID tenantId, Actor actor, TenantOidcConfigRequest request) {
        return repository.findByIdAndTenantId(id, tenantId)
            .map(existing -> {
                boolean changed = !request.discoveryUri().equals(existing.discoveryUri())
                        || !request.clientId().equals(existing.clientId())
                        || !request.clientSecret().equals(existing.clientSecret());
                if (!changed) {
                    return existing;
                }
                TenantOidcConfig saved = repository.save(new TenantOidcConfig(existing.id(), existing.tenantId(),
                    request.discoveryUri(), request.clientId(), request.clientSecret(),
                    existing.enabled(), existing.createdAt(), Instant.now(), existing.deletedAt()));
                audit(saved, actor, "updated", Map.of("discoveryUri", saved.discoveryUri(),
                    "clientId", saved.clientId(),
                    "clientSecretChanged", !existing.clientSecret().equals(saved.clientSecret())));
                return saved;
            });
    }

    @Transactional
    public void delete(UUID id, UUID tenantId, Actor actor) {
        repository.findByIdAndTenantId(id, tenantId).ifPresent(existing -> {
            repository.softDeleteByIdAndTenantId(id, tenantId);
            audit(existing, actor, "deleted", Map.of("discoveryUri", existing.discoveryUri()));
        });
    }

    /** Never the client secret: only whether it changed. */
    private void audit(TenantOidcConfig config, Actor actor, String action, Map<String, Object> payload) {
        auditEventPort.record(AuditEvent.of(config.tenantId(), AuditKind.OIDC_CONFIG, config.id(), action, actor, payload));
    }
}
