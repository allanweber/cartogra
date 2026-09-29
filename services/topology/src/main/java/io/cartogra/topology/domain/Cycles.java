package io.cartogra.topology.domain;

import java.util.List;

public record Cycles(List<Cycle> cycles, boolean truncated) {
}
