package io.cartogra.common.api;

public record ApiErrorResponse(ApiError error, String traceId) {
    public static ApiErrorResponse of(ApiError error, String traceId) {
        return new ApiErrorResponse(error, traceId);
    }
}