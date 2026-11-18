package io.cartogra.contract.domain;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.List;

/** One difference between two canonical specs, located by an RFC 6901 JSON pointer. */
public record SpecChange(Op op, String pointer, @Nullable JsonNode before, @Nullable JsonNode after) {

    public enum Op { ADDED, REMOVED, CHANGED }

    /** The pointer's unescaped segments, e.g. {@code ["paths", "/a/{id}"]}. */
    public List<String> segments() {
        return pointer.isEmpty() ? List.of() : Arrays.stream(pointer.substring(1).split("/", -1))
                .map(s -> s.replace("~1", "/").replace("~0", "~"))
                .toList();
    }
}
