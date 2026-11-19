package io.cartogra.contract.domain;

import java.util.List;

/** The verdict of the breaking-change engine: breaking when at least one change is breaking. */
public record Compatibility(boolean isBreaking, List<CompatibilityChange> changes) {

    public static Compatibility of(List<CompatibilityChange> changes) {
        return new Compatibility(changes.stream().anyMatch(CompatibilityChange::breaking), List.copyOf(changes));
    }
}
