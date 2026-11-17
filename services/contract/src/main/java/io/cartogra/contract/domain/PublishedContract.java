package io.cartogra.contract.domain;

import org.jspecify.annotations.Nullable;

/** A contract with its latest version; {@code serviceName} is null until the service projection has seen it. */
public record PublishedContract(ApiContract contract, ContractVersion latestVersion, @Nullable String serviceName) {}
