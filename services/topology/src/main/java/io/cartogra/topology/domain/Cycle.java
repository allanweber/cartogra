package io.cartogra.topology.domain;

import java.util.List;
import java.util.UUID;

/** Always stored in rotated-canonical form — see {@link CycleService#rotateToCanonical}. */
public record Cycle(List<UUID> members) {
}
