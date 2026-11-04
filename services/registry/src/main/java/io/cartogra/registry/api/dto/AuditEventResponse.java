package io.cartogra.registry.api.dto;

import io.cartogra.registry.domain.AuditEntry;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
    private static final Set<String> PERSONAL_PAYLOAD_KEYS = Set.of("userId", "email");

    public static AuditEventResponse from(AuditEntry entry) {
        var e = entry.event();
        return new AuditEventResponse(e.eventId(), e.entityType(), e.entityId(), e.action(),
                e.actorKind().toDbValue(), e.actorId(), entry.actorName(), e.payload(), e.occurredAt());
    }

    /** Drops who-did-it identifiers: the actor id and any user id or email inside the payload. */
    public AuditEventResponse withoutPersonalData() {
        Map<String, Object> redacted = new LinkedHashMap<>(payload);
        PERSONAL_PAYLOAD_KEYS.forEach(redacted::remove);
        return new AuditEventResponse(eventId, entityType, entityId, action, actorKind, null, actorName,
                redacted, occurredAt);
    }
}
