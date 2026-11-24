package io.cartogra.gateway.config;

import java.util.Map;
import java.util.Optional;

/**
 * The API Key Allowlist: the endpoints where the gateway reads the API key header, and the scope that each
 * one needs. On each other path the header is ignored.
 */
public final class ApiKeyAllowlist {

    public static final String HEADER = "X-Cartogra-Api-Key";

    private static final Map<String, String> SCOPE_BY_ENDPOINT = Map.of(
            "POST /api/v1/contracts/check", "ci:check");

    private ApiKeyAllowlist() {
    }

    /**
     * Returns the scope that the endpoint needs, or empty if the endpoint is not on the allowlist. One trailing
     * slash is ignored. Each other path variant is not on the allowlist.
     */
    public static Optional<String> requiredScope(String method, String path) {
        String normalized = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return Optional.ofNullable(SCOPE_BY_ENDPOINT.get(method + " " + normalized));
    }
}
