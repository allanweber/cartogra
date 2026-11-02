package io.cartogra.registry.domain;

import io.cartogra.common.audit.AuditEvent;
import org.jspecify.annotations.Nullable;

/** A stored audit event plus the actor's display name, resolved from {@code users} at read time. */
public record AuditEntry(AuditEvent event, @Nullable String actorName) {}
