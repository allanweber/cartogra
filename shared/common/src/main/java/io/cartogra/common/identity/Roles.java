package io.cartogra.common.identity;

/**
 * Role names and the {@code @PreAuthorize} expressions every write path uses. {@code TEAM_OWNER} is a
 * legacy role (ADR-0025) still accepted at member tier until it leaves the role vocabulary.
 */
public final class Roles {

    private Roles() {}

    public static final String GUEST = "GUEST";

    public static final String CAN_WRITE = "hasAnyRole('ADMIN', 'MEMBER', 'TEAM_OWNER')";

    public static final String ADMIN_ONLY = "hasRole('ADMIN')";
}
