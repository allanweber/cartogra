package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.RiskPage;

import java.util.List;

public record RiskPageResponse(List<RiskResponse> items, long total, int limit, int offset, boolean truncated) {
    public static RiskPageResponse from(RiskPage page) {
        return new RiskPageResponse(page.items().stream().map(RiskResponse::from).toList(), page.total(),
                page.limit(), page.offset(), page.truncated());
    }
}
