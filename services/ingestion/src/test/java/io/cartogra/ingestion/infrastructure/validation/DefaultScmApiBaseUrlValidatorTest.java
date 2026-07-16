package io.cartogra.ingestion.infrastructure.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultScmApiBaseUrlValidatorTest {

    // Production: https only, private IPs blocked
    private final DefaultScmApiBaseUrlValidator strictValidator =
            new DefaultScmApiBaseUrlValidator(false, false, "");

    // Same-network GHES/AzDO Server deployment: http allowed, private IPs allowed
    private final DefaultScmApiBaseUrlValidator devValidator =
            new DefaultScmApiBaseUrlValidator(true, true, "");

    // CIDR allowlist: http allowed, only 10.0.0.0/8 permitted as private range
    private final DefaultScmApiBaseUrlValidator cidrValidator =
            new DefaultScmApiBaseUrlValidator(true, false, "10.0.0.0/8");

    @Test
    void httpsPublicUrlPassesInProduction() {
        // 203.0.113.x is TEST-NET-3 (documentation range) — public, not private/reserved, no DNS needed
        assertThatCode(() -> strictValidator.validate("https://203.0.113.1/api/v3"))
                .doesNotThrowAnyException();
    }

    @Test
    void publicHostnameResolvesAndPasses() {
        assertThatCode(() -> strictValidator.validate("https://api.github.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void apiBaseUrlAbsentIsCallerResponsibilityNotValidatorConcern() {
        // The validator only validates a value it's given; ScmConnectionService is responsible
        // for not calling validate() at all when apiBaseUrl is absent from config. Documented here
        // so the contract stays explicit: a blank value is always rejected, never silently skipped.
        assertThatThrownBy(() -> strictValidator.validate(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void httpPublicUrlPassesWhenAllowHttpTrue() {
        assertThatCode(() -> devValidator.validate("http://203.0.113.1/api/v3"))
                .doesNotThrowAnyException();
    }

    @Test
    void httpPublicUrlBlockedWhenAllowHttpFalse() {
        assertThatThrownBy(() -> strictValidator.validate("http://203.0.113.1/api/v3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    // --- Private range behaviour ---

    @ParameterizedTest
    @ValueSource(strings = {
            "http://10.0.0.1/api/v3",
            "http://10.255.255.255/api/v3",
            "http://172.16.0.1/api/v3",
            "http://172.31.0.1/api/v3",
            "http://192.168.1.1/api/v3",
    })
    void privateIpv4RangesBlockedByDefault(String url) {
        assertThatThrownBy(() -> strictValidator.validate(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private address");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://10.0.0.1/api/v3",
            "http://172.16.0.1/api/v3",
            "http://192.168.1.1/api/v3",
    })
    void privateIpv4RangesAllowedWhenFlagEnabled(String url) {
        assertThatCode(() -> devValidator.validate(url))
                .doesNotThrowAnyException();
    }

    @Test
    void allowedCidrPermitsMatchingPrivateAddress() {
        assertThatCode(() -> cidrValidator.validate("http://10.0.1.42/api/v3"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowedCidrBlocksNonMatchingPrivateAddress() {
        assertThatThrownBy(() -> cidrValidator.validate("http://192.168.1.1/api/v3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private address");
    }

    // --- Hard-blocked ranges — always rejected regardless of any flag ---

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/api/v3",
            "http://127.255.255.255/api/v3",
            "http://169.254.169.254/latest/meta-data",
            "http://169.254.0.1/api/v3",
    })
    void loopbackAndLinkLocalAlwaysBlocked(String url) {
        // devValidator has allowPrivateEndpoints=true — these must still be rejected
        assertThatThrownBy(() -> devValidator.validate(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback or link-local");
    }

    @Test
    void cloudMetadataEndpointAlwaysBlocked() {
        // 169.254.169.254 is the cloud metadata API — must be blocked unconditionally, even though
        // it's the exact SSRF payload a malicious apiBaseUrl would use to steal instance credentials
        assertThatThrownBy(() -> devValidator.validate("http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback or link-local");
    }

    @Test
    void localhostHostnameBlockedViaResolution() {
        assertThatThrownBy(() -> devValidator.validate("http://localhost/api/v3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback or link-local");
    }

    // --- Scheme and URL parsing ---

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/api/v3",
            "file:///etc/passwd",
            "ldap://example.com"
    })
    void nonHttpSchemesAreBlocked(String url) {
        assertThatThrownBy(() -> strictValidator.validate(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-a-url",
            "   "
    })
    void unparseableOrBlankUrlsAreBlocked(String url) {
        assertThatThrownBy(() -> strictValidator.validate(url))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
