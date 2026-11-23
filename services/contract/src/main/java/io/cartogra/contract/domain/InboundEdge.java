package io.cartogra.contract.domain;

import java.util.UUID;

/** One topology edge as contract stores it; {@code dependencyType} and {@code protocol} are topology's db values. */
public record InboundEdge(
        UUID edgeId,
        UUID sourceServiceId,
        UUID targetServiceId,
        String dependencyType,
        String protocol
) {
}
