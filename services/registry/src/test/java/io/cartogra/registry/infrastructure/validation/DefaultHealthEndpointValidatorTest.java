package io.cartogra.registry.infrastructure.validation;

import io.cartogra.registry.domain.exception.InvalidHealthEndpointException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultHealthEndpointValidatorTest {

    // Production: https only, private IPs blocked
    private final DefaultHealthEndpointValidator strictValidator =
            new DefaultHealthEndpointValidator(false, false, "");

    // Dev: http allowed, private IPs allowed (same-network deployments)
    private final DefaultHealthEndpointValidator devValidator =
            new DefaultHealthEndpointValidator(true, true, "");

    // CIDR allowlist: http allowed, only 10.0.0.0/8 permitted as private range
    private final DefaultHealthEndpointValidator cidrValidator =
            new DefaultHealthEndpointValidator(true, false, "10.0.0.0/8");

    @Test
    void httpsPublicUrlPassesInProduction() {
        // 203.0.113.x is TEST-NET-3 (documentation range) — public, not private/reserved, no DNS needed
        assertThatCode(() -> strictValidator.validate("https://203.0.113.1/health"))
                .doesNotThrowAnyException();
    }

    @Test
    void httpPublicUrlPassesWhenAllowHttpTrue() {
        // 203.0.113.x is TEST-NET-3 (documentation range) — public, not private/reserved, no DNS needed
        assertThatCode(() -> devValidator.validate("http://203.0.113.1/health"))
                .doesNotThrowAnyException();
    }

    @Test
    void httpPublicUrlBlockedWhenAllowHttpFalse() {
        // Scheme check fires before DNS resolution so the exception is scheme-related, not DNS
        assertThatThrownBy(() -> strictValidator.validate("http://203.0.113.1/health"))
                .isInstanceOf(InvalidHealthEndpointException.class)
                .hasMessageContaining("HTTP endpoints are not allowed");
    }

    // --- Private range behaviour ---

    @ParameterizedTest
    @ValueSource(strings = {
            "http://10.0.0.1/health",
            "http://10.255.255.255/health",
            "http://172.16.0.1/health",
            "http://172.31.0.1/health",
            "http://192.168.1.1/health",
    })
    void privateIpv4RangesBlockedByDefault(String url) {
        assertThatThrownBy(() -> strictValidator.validate(url))
                .isInstanceOf(InvalidHealthEndpointException.class)
                .hasMessageContaining("private address");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://10.0.0.1/health",
            "http://172.16.0.1/health",
            "http://192.168.1.1/health",
    })
    void privateIpv4RangesAllowedWhenFlagEnabled(String url) {
        assertThatCode(() -> devValidator.validate(url))
                .doesNotThrowAnyException();
    }

    @Test
    void allowedCidrPermitsMatchingPrivateAddress() {
        assertThatCode(() -> cidrValidator.validate("http://10.0.1.42/health"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowedCidrBlocksNonMatchingPrivateAddress() {
        assertThatThrownBy(() -> cidrValidator.validate("http://192.168.1.1/health"))
                .isInstanceOf(InvalidHealthEndpointException.class)
                .hasMessageContaining("private address");
    }

    // --- Hard-blocked ranges — always rejected regardless of any flag ---

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/health",
            "http://127.255.255.255/health",
            "http://169.254.169.254/health",
            "http://169.254.0.1/health",
    })
    void loopbackAndLinkLocalAlwaysBlocked(String url) {
        // devValidator has allowPrivateEndpoints=true — these must still be rejected
        assertThatThrownBy(() -> devValidator.validate(url))
                .isInstanceOf(InvalidHealthEndpointException.class)
                .hasMessageContaining("loopback or link-local");
    }

    @Test
    void awsMetadataEndpointAlwaysBlocked() {
        // 169.254.169.254 is the cloud metadata API — must be blocked unconditionally
        assertThatThrownBy(() -> devValidator.validate("http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(InvalidHealthEndpointException.class)
                .hasMessageContaining("loopback or link-local");
    }

    @Test
    void loopbackBlockedEvenWhenPrivateEndpointsAllowed() {
        assertThatThrownBy(() -> devValidator.validate("http://127.0.0.1/health"))
                .isInstanceOf(InvalidHealthEndpointException.class)
                .hasMessageContaining("loopback or link-local");
    }

    // --- Scheme and URL parsing ---

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/health",
            "file:///etc/passwd",
            "ldap://example.com"
    })
    void nonHttpSchemesAreBlocked(String url) {
        assertThatThrownBy(() -> strictValidator.validate(url))
                .isInstanceOf(InvalidHealthEndpointException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-a-url",
            "",
            "   "
    })
    void unparseableUrlsAreBlocked(String url) {
        assertThatThrownBy(() -> strictValidator.validate(url))
                .isInstanceOf(InvalidHealthEndpointException.class);
    }
}
