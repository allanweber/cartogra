package io.cartogra.gateway.api.dto;

public record ApiResponse<T>(T data, String traceId) {}
