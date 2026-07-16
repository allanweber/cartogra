package io.cartogra.ingestion.infrastructure.validation;

public interface ScmApiBaseUrlValidator {
    /**
     * Validates that the given tenant-supplied SCM {@code apiBaseUrl} is safe to use as the base
     * URL for a {@code RestClient} that will carry the connection's live credential on every
     * request. Throws {@link IllegalArgumentException} if the URL resolves to a loopback,
     * link-local, or (by default) private-range address — this is an SSRF guard, not a strict
     * allowlist, since genuinely custom GitHub Enterprise Server / Azure DevOps Server hosts must
     * remain supported.
     */
    void validate(String apiBaseUrl);
}
