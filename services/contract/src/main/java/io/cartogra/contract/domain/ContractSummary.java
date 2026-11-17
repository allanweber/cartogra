package io.cartogra.contract.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/** A contract row of the list; {@code serviceName} is null until the service projection has seen the service. */
public record ContractSummary(
        UUID id,
        UUID serviceId,
        @Nullable String serviceName,
        VersionHeader latestVersion,
        Instant updatedAt
) {}
