package io.cartogra.topology.domain;

import java.util.List;

public record RiskPage(List<Risk> items, long total, int limit, int offset, boolean truncated) {
}
