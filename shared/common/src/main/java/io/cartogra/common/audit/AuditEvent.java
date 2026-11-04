package io.cartogra.common.audit;

import io.cartogra.common.identity.Actor;
import io.cartogra.common.identity.SystemActors;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One state change worth showing on the Timeline (ADR-0017). {@code eventId} is the idempotency key the
 * registry dedupes on; {@code actorId} is null exactly when {@code actorKind} is {@link ActorKind#SYSTEM}.
 */
public record AuditEvent(
        UUID eventId,
        UUID tenantId,
        String entityType,
        UUID entityId,
        String action,
        ActorKind actorKind,
        UUID actorId,
        Map<String, Object> payload,
        Instant occurredAt
) {
    public AuditEvent {
        payload = payload == null ? Map.of() : payload;
        if (actorKind == ActorKind.SYSTEM) {
            actorId = null;
        }
    }

    /** {@link Actor#SYSTEM}, or an actor with no user id, records a system change; anything else a user change. */
    public static AuditEvent of(UUID tenantId, String entityType, UUID entityId, String action,
                                Actor actor, Map<String, Object> payload) {
        boolean system = actor.userId() == null || SystemActors.SYSTEM.equals(actor.userId());
        return new AuditEvent(UUID.randomUUID(), tenantId, entityType, entityId, action,
                system ? ActorKind.SYSTEM : ActorKind.USER, actor.userId(), payload, Instant.now());
    }
}
