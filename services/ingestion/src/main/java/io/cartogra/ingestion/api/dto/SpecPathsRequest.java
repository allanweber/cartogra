package io.cartogra.ingestion.api.dto;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record SpecPathsRequest(@Nullable List<String> custom) {}
