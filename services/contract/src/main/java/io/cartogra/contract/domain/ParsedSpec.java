package io.cartogra.contract.domain;

import org.jspecify.annotations.Nullable;

/** A validated spec: key-sorted compact JSON, its SHA-256 hex, and {@code info.version} as a display label. */
public record ParsedSpec(String canonical, String hash, @Nullable String label) {}
