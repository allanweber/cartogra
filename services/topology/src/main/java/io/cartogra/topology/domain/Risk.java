package io.cartogra.topology.domain;

import java.util.List;
import java.util.UUID;

/**
 * A single finding surfaced on {@code GET /v1/risks}. {@code id} is deterministic (derived from
 * the finding's own identity — a service id, a drift record id, or a cycle's canonical member
 * list) rather than a stored primary key, since SPOF/cycle/orphan findings are computed fresh on
 * every request.
 */
public record Risk(
        String id,
        RiskType type,
        RiskSeverity severity,
        String title,
        String explanation,
        String fix,
        List<UUID> affectedServices
) {
}
