package io.cartogra.topology.repository;

import java.time.Instant;
import java.util.UUID;

public record EdgePairStat(UUID sourceServiceId, UUID targetServiceId, Instant firstCreatedAt, Instant lastActiveAt) {
}
