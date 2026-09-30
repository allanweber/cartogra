package io.cartogra.topology.domain;

import io.cartogra.topology.repository.DependencyGraphViewRepository;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Finds elementary cycles (closed directed walks that revisit no interior node) over the
 * tenant's declared/observed dependency edges, normalized to one entry per distinct cycle
 * regardless of which member the underlying recursive query happened to walk first.
 */
@org.springframework.stereotype.Service
public class CycleService {

    public static final int MAX_CYCLE_LENGTH = 8;
    public static final int MAX_RAW_ROWS = 5000;
    public static final int MAX_CYCLES = 200;

    private final DependencyGraphViewRepository graphViewRepository;

    public CycleService(DependencyGraphViewRepository graphViewRepository) {
        this.graphViewRepository = graphViewRepository;
    }

    public Cycles find(UUID tenantId, @Nullable DependencyType type) {
        List<List<UUID>> rawPaths = graphViewRepository.findCycles(tenantId, type, MAX_CYCLE_LENGTH, MAX_RAW_ROWS + 1);
        boolean rawRowCapHit = rawPaths.size() > MAX_RAW_ROWS;
        List<List<UUID>> paths = rawRowCapHit ? rawPaths.subList(0, MAX_RAW_ROWS) : rawPaths;

        Set<List<UUID>> normalized = new LinkedHashSet<>();
        for (List<UUID> path : paths) {
            // Each raw path repeats its start id as the last element (e.g. [A, B, C, A]) —
            // drop it before rotating, or the same cycle would rotate to different-length keys
            // depending on which member the recursive query happened to start from.
            List<UUID> members = path.subList(0, path.size() - 1);
            normalized.add(rotateToCanonical(members));
        }

        boolean cycleCapHit = normalized.size() > MAX_CYCLES;
        List<Cycle> cycles = normalized.stream().limit(MAX_CYCLES).map(Cycle::new).toList();

        return new Cycles(cycles, rawRowCapHit || cycleCapHit);
    }

    /**
     * Rotates {@code members} so the smallest {@link UUID} (an arbitrary but total order, so
     * always reproducible) comes first — collapses the same directed cycle found starting from
     * any of its members down to one canonical form. Never reversed: a cycle is directed, so
     * A→B→C→A and A→C→B→A are different cycles even though they share the same members.
     */
    static List<UUID> rotateToCanonical(List<UUID> members) {
        int minIndex = 0;
        for (int i = 1; i < members.size(); i++) {
            if (members.get(i).compareTo(members.get(minIndex)) < 0) {
                minIndex = i;
            }
        }
        List<UUID> rotated = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            rotated.add(members.get((minIndex + i) % members.size()));
        }
        return rotated;
    }
}
