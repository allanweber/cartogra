package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.Risk;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public record RiskResponse(
        String id,
        String type,
        String severity,
        String title,
        String explanation,
        String fix,
        List<UUID> affectedServices
) {
    public static RiskResponse from(Risk risk) {
        return new RiskResponse(risk.id(), risk.type().name().toLowerCase(Locale.ROOT),
                risk.severity().name().toLowerCase(Locale.ROOT), risk.title(), risk.explanation(), risk.fix(),
                risk.affectedServices());
    }
}
