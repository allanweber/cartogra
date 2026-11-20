package io.cartogra.contract.domain;

import org.jspecify.annotations.Nullable;

import java.util.List;

/** Version {@code to} against the one before it; {@code from} is null for version 1. */
public record VersionDiff(@Nullable VersionHeader from, VersionHeader to, List<SpecChange> changes,
                          List<DiffLine> lines, Compatibility compatibility) {}
