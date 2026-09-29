package io.cartogra.topology.domain;

/**
 * Traversal direction for a blast-radius query. Deliberately the OPPOSITE of
 * {@link DependencyService}'s "upstream"/"downstream" (that feature's downstream is "this
 * service depends on"; here, {@link #UPSTREAM} is "what this service depends on"). See
 * {@link BlastRadiusService} for the full explanation.
 */
public enum BlastRadiusDirection {
    UPSTREAM,
    DOWNSTREAM
}
