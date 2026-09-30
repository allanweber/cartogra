package io.cartogra.topology.domain;

import java.util.List;

public record SpofResult(int threshold, String rationale, List<Spof> items) {
}
