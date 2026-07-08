package io.cartogra.common.api;

public final class ErrorCodes {
    private ErrorCodes() {}

    public static final String BAD_REQUEST      = "BAD_REQUEST";
    public static final String NOT_FOUND        = "NOT_FOUND";
    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String CONFLICT         = "CONFLICT";
    public static final String UNAUTHORIZED     = "UNAUTHORIZED";
    public static final String FORBIDDEN        = "FORBIDDEN";
    public static final String RATE_LIMITED     = "RATE_LIMITED";
    public static final String INTERNAL_ERROR          = "INTERNAL_ERROR";
    public static final String SCM_PROVIDER_ERROR      = "SCM_PROVIDER_ERROR";
    public static final String SCM_PROVIDER_NOT_SUPPORTED = "SCM_PROVIDER_NOT_SUPPORTED";
    public static final String SYNC_JOB_NOT_FOUND          = "SYNC_JOB_NOT_FOUND";
    public static final String INVALID_HEALTH_ENDPOINT      = "INVALID_HEALTH_ENDPOINT";
    public static final String WEBHOOK_SIGNATURE_INVALID     = "WEBHOOK_SIGNATURE_INVALID";
    public static final String WEBHOOK_CONNECTION_NOT_FOUND  = "WEBHOOK_CONNECTION_NOT_FOUND";
    public static final String SERVICE_UNAVAILABLE           = "SERVICE_UNAVAILABLE";
}
