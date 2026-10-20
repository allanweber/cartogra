package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.DriftPage;

import java.util.List;

public record DriftPageResponse(List<DriftResponse> items, long total, int limit, int offset) {
    public static DriftPageResponse from(DriftPage page) {
        return new DriftPageResponse(page.items().stream().map(DriftResponse::from).toList(), page.total(),
                page.limit(), page.offset());
    }
}
