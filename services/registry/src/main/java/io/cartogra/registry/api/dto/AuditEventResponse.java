package io.cartogra.registry.api.dto;

import io.cartogra.registry.domain.AuditEntry;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventResponse(
        UUID eventId,
        String entityType,
        UUID entityId,
        String action,
        String actorKind,
        UUID actorId,
        String actorName,
        Map<String, Object> payload,
        Instant occurredAt
) {
    public static AuditEventResponse from(AuditEntry entry) {
        var e = entry.event();
        return new AuditEventResponse(e.eventId(), e.entityType(), e.entityId(), e.action(),
                e.actorKind().toDbValue(), e.actorId(), entry.actorName(), e.payload(), e.occurredAt());
    }
}
