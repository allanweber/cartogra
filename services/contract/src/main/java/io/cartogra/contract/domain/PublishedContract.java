package io.cartogra.contract.domain;

public record PublishedContract(ApiContract contract, ContractVersion latestVersion) {}
