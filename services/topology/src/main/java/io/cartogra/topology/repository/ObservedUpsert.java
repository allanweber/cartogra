package io.cartogra.topology.repository;

import io.cartogra.topology.domain.Dependency;
import org.jspecify.annotations.Nullable;

/** {@code edge} is the stored row after the upsert; null only when the outcome is {@link UpsertOutcome#UNCHANGED}. */
public record ObservedUpsert(UpsertOutcome outcome, @Nullable Dependency edge) {

    public static ObservedUpsert unchanged() {
        return new ObservedUpsert(UpsertOutcome.UNCHANGED, null);
    }
}
