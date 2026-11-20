package io.cartogra.contract.domain;

/** The state of a discovered spec. Only PENDING has work to do; the other states are final. */
public enum DiscoveredSpecStatus {
    PENDING,
    APPLIED,
    UNCHANGED,
    REJECTED,
    SKIPPED,
    /** A newer spec came for the same repository and path before this one was applied. */
    SUPERSEDED
}
