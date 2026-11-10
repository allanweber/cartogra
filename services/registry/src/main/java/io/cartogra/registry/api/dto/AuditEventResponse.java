package io.cartogra.registry.api.dto;

import io.cartogra.common.audit.AuditKind;
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

    /** Drops the actor id and keeps only the payload fields the entity's {@link AuditKind} shows guests. */
    public AuditEventResponse forGuest() {
        Map<String, Object> visible = AuditKind.fromEntityType(entityType)
                .map(kind -> kind.guestPayload(payload))
                .orElseGet(Map::of);
        return new AuditEventResponse(eventId, entityType, entityId, action, actorKind, null, actorName,
                visible, occurredAt);
    }
}
