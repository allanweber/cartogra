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
        @Nullable Instant to,
        List<String> excludedEntityTypes,
        boolean redactActorEmails
) {
    public AuditEventFilter(@Nullable List<String> entityTypes, @Nullable UUID entityId,
            @Nullable ActorKind actorKind, @Nullable Instant from, @Nullable Instant to) {
        this(entityTypes, entityId, actorKind, from, to, List.of(), false);
    }

    public static AuditEventFilter empty() {
        return new AuditEventFilter(null, null, null, null, null);
    }

    /** Drops the given entity types and never falls back to an actor's email for their display name. */
    public AuditEventFilter redacted(List<String> hiddenEntityTypes) {
        return new AuditEventFilter(entityTypes, entityId, actorKind, from, to, List.copyOf(hiddenEntityTypes), true);
    }
}
