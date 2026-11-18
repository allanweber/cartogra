package io.cartogra.contract.domain;

import org.jspecify.annotations.Nullable;

/** One aligned row of the side-by-side diff; a side is null when the row exists on the other side only. */
public record DiffLine(Kind kind, @Nullable Integer leftNo, @Nullable String left,
                       @Nullable Integer rightNo, @Nullable String right) {

    public enum Kind { SAME, ADDED, REMOVED, CHANGED }
}
