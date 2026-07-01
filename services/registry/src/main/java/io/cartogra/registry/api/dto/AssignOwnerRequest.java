package io.cartogra.registry.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AssignOwnerRequest(@NotNull UUID teamId) {}
