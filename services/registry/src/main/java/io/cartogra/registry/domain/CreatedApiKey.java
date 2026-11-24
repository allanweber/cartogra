package io.cartogra.registry.domain;

/**
 * The result of a create: the stored key and the full key. The full key exists only in this value.
 */
public record CreatedApiKey(ApiKey apiKey, String key) {}
