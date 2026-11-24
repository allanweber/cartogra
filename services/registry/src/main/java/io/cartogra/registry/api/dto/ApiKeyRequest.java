package io.cartogra.registry.api.dto;

import io.cartogra.common.validation.NoHtml;

import java.time.Instant;
import java.util.List;

public record ApiKeyRequest(@NoHtml String name, List<String> scopes, Instant expiresAt) {}
