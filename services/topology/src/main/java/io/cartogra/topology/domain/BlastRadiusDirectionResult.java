package io.cartogra.topology.domain;

import java.util.List;

/**
 * Truncation is split into two independent causes rather than one generic flag, because they
 * need distinct UI copy:
 * <ul>
 *   <li>{@code depthTruncated}/{@code nodesBeyondDepth} — the traversal hit {@code maxDepth}.
 *       {@code nodesBeyondDepth} is a lower bound: the count of distinct services first reachable
 *       at exactly {@code maxDepth + 1} (one "peek" hop past the cap), so it can undercount
 *       services further out.</li>
 *   <li>{@code nodeCapTruncated}/{@code nodesBeyondCap} — the within-depth result set exceeded
 *       {@link BlastRadiusService#MAX_NODES_PER_DIRECTION}. {@code nodesBeyondCap} is exact.</li>
 * </ul>
 */
public record BlastRadiusDirectionResult(
        List<BlastRadiusEntry> entries,
        boolean depthTruncated,
        int nodesBeyondDepth,
        boolean nodeCapTruncated,
        int nodesBeyondCap
) {
    public static BlastRadiusDirectionResult empty() {
        return new BlastRadiusDirectionResult(List.of(), false, 0, false, 0);
    }
}
