package io.cartogra.common.event;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope<P>(
        UUID eventId, String eventType, UUID entityId, UUID tenantId,
        Instant timestamp, int version, UUID correlationId, P payload) {

    public static <P> EventEnvelope<P> of(String eventType, UUID entityId, UUID tenantId, int version, P payload) {
        Instant now = Instant.now();
        return new EventEnvelope<>(
                UuidV5.fromNames(eventType, entityId.toString(), now.toString()),
                eventType, entityId, tenantId, now, version, UUID.randomUUID(), payload);
    }
}
