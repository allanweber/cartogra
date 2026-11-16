package io.cartogra.contract.domain;

/** {@code created} is false when the spec matched the latest version and no new version was stored. */
public record VersionPublication(ContractVersion version, boolean created) {}
