package io.cartogra.contract.domain;

import java.util.List;

/** The answer to a check. {@code affectedConsumers} is empty when the candidate is compatible. */
public record CheckResult(
        boolean isBreaking,
        int baselineVersion,
        List<CompatibilityChange> changes,
        List<Consumer> affectedConsumers
) {}
