package io.cartogra.registry.repository;

import io.cartogra.registry.domain.AuditEntry;

import java.util.List;
import java.util.UUID;

public interface AuditEventRepository {

    List<AuditEntry> findAll(UUID tenantId, AuditEventFilter filter, int limit, int offset);

    long count(UUID tenantId, AuditEventFilter filter);
}
