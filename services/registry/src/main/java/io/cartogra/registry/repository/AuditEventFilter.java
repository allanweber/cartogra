package io.cartogra.registry.repository;

import io.cartogra.common.audit.ActorKind;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AuditEventFilter(
        @Nullable List<String> entityTypes,
        @Nullable UUID entityId,
        @Nullable ActorKind actorKind,
        @Nullable Instant from,
        @Nullable Instant to
) {
    public static AuditEventFilter empty() {
        return new AuditEventFilter(null, null, null, null, null);
    }
}
