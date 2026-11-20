package io.cartogra.ingestion.api.dto;

import io.cartogra.ingestion.domain.SpecPaths;

import java.util.List;

public record SpecPathsResponse(List<String> defaults, List<String> custom) {

    public static SpecPathsResponse from(SpecPaths paths) {
        return new SpecPathsResponse(paths.defaults(), paths.custom());
    }
}
