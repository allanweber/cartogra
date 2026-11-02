package io.cartogra.registry.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.common.audit.AuditEvent;
import io.cartogra.common.audit.AuditEventPort;
import io.cartogra.registry.repository.AuditEventFilter;
import io.cartogra.registry.repository.AuditEventRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuditEventService {

    private static final int MAX_LIMIT = 200;

    private final AuditEventRepository repository;
    private final AuditEventPort auditEventPort;

    public AuditEventService(AuditEventRepository repository, AuditEventPort auditEventPort) {
        this.repository = repository;
        this.auditEventPort = auditEventPort;
    }

    public PageResult<AuditEntry> list(UUID tenantId, AuditEventFilter filter, int limit, int offset) {
        int boundedLimit = Math.clamp(limit, 1, MAX_LIMIT);
        int boundedOffset = Math.max(offset, 0);
        return PageResult.of(repository.findAll(tenantId, filter, boundedLimit, boundedOffset),
                repository.count(tenantId, filter), boundedLimit, boundedOffset);
    }

    /** Persists an event another service published; a redelivered {@code eventId} is a no-op. */
    public void recordPublished(AuditEvent event) {
        auditEventPort.record(event);
    }
}
