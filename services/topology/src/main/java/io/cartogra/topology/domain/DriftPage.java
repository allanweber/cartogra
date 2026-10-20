package io.cartogra.topology.domain;

import java.util.List;

public record DriftPage(List<DependencyDrift> items, long total, int limit, int offset) {
}
