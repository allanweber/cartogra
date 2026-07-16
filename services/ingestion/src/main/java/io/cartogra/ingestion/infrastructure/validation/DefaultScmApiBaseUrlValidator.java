package io.cartogra.ingestion.infrastructure.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * SSRF guard for tenant-supplied SCM {@code apiBaseUrl} values (GitHub/Azure DevOps connection
 * config). Mirrors {@code io.cartogra.registry.infrastructure.validation.DefaultHealthEndpointValidator}'s
 * blocklist approach and its documented DNS-rebinding tradeoff: this is a blocklist of dangerous
 * targets (loopback, link-local incl. the cloud metadata endpoint, private ranges), not an
 * allowlist of known SCM hosts, so genuinely custom GitHub Enterprise Server / Azure DevOps
 * Server hosts remain supported.
 */
@Component
public class DefaultScmApiBaseUrlValidator implements ScmApiBaseUrlValidator {

    private static final Logger logger = LoggerFactory.getLogger(DefaultScmApiBaseUrlValidator.class);

    private final boolean allowHttp;
    private final boolean allowPrivateEndpoints;
    private final List<CidrBlock> allowedCidrs;

    public DefaultScmApiBaseUrlValidator(
            @Value("${ingestion.scm.allow-http-endpoints:false}") boolean allowHttp,
            @Value("${ingestion.scm.allow-private-endpoints:false}") boolean allowPrivateEndpoints,
            @Value("${ingestion.scm.allowed-cidrs:}") String allowedCidrsRaw) {
        this.allowHttp = allowHttp;
        this.allowPrivateEndpoints = allowPrivateEndpoints;
        this.allowedCidrs = parseCidrs(allowedCidrsRaw);
    }

    @Override
    public void validate(String apiBaseUrl) {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            throw new IllegalArgumentException("apiBaseUrl must not be blank");
        }

        URI uri;
        try {
            uri = new URI(apiBaseUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("apiBaseUrl is not a valid URL: " + apiBaseUrl);
        }

        String scheme = uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalArgumentException("apiBaseUrl must use http or https: " + apiBaseUrl);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("apiBaseUrl has no host: " + apiBaseUrl);
        }

        // Resolve hostname before scheme enforcement so that private/loopback addresses are
        // rejected with an informative SSRF message even when http is also disallowed.
        // DNS rebinding after write time is an accepted residual risk, same tradeoff the registry
        // health-endpoint validator documents: connection create/update is an infrequent,
        // operator-driven write, not a hot path an attacker can easily race.
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            logger.warn("Cannot resolve SCM apiBaseUrl host '{}': {}", host, e.getMessage());
            throw new IllegalArgumentException("Cannot resolve apiBaseUrl host: " + host);
        }

        for (InetAddress addr : addresses) {
            if (isHardBlocked(addr)) {
                throw new IllegalArgumentException(
                        "apiBaseUrl resolves to a loopback or link-local address: " + apiBaseUrl);
            }
            if (isSoftBlocked(addr) && !allowPrivateEndpoints && !isInAllowedCidr(addr)) {
                throw new IllegalArgumentException(
                        "apiBaseUrl resolves to a private address; set ingestion.scm.allow-private-endpoints=true "
                                + "or add the CIDR to ingestion.scm.allowed-cidrs if this is a genuine on-prem GHES/AzDO Server host: "
                                + apiBaseUrl);
            }
        }

        if ("http".equals(scheme) && !allowHttp) {
            throw new IllegalArgumentException(
                    "apiBaseUrl must use HTTPS; set ingestion.scm.allow-http-endpoints=true to allow HTTP: " + apiBaseUrl);
        }
    }

    // Always blocked: loopback (127/8, ::1) and link-local (169.254/16, fe80::/10).
    // The cloud metadata service (169.254.169.254) falls in the link-local range.
    private boolean isHardBlocked(InetAddress addr) {
        return addr.isLoopbackAddress() || addr.isLinkLocalAddress();
    }

    // Blocked by default; overridable via allow-private-endpoints or allowed-cidrs.
    // Covers IPv4 site-local (10/8, 172.16/12, 192.168/16) and IPv6 ULA (fc00::/7).
    private boolean isSoftBlocked(InetAddress addr) {
        if (addr.isSiteLocalAddress()) return true;
        if (addr instanceof Inet6Address) {
            byte[] bytes = addr.getAddress();
            return (bytes[0] & 0xFE) == 0xFC;  // fc00::/7 — covers fd00::/8 and reserved fc00::/8
        }
        return false;
    }

    private boolean isInAllowedCidr(InetAddress addr) {
        return allowedCidrs.stream().anyMatch(cidr -> cidr.contains(addr));
    }

    private static List<CidrBlock> parseCidrs(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(CidrBlock::parse)
                .toList();
    }

    private record CidrBlock(byte[] networkAddress, int prefixLength) {

        static CidrBlock parse(String cidr) {
            String[] parts = cidr.split("/", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid CIDR (missing prefix length): " + cidr);
            }
            try {
                InetAddress addr = InetAddress.getByName(parts[0]);
                int prefix = Integer.parseInt(parts[1].strip());
                return new CidrBlock(addr.getAddress(), prefix);
            } catch (UnknownHostException | NumberFormatException e) {
                throw new IllegalArgumentException("Invalid CIDR: " + cidr, e);
            }
        }

        boolean contains(InetAddress addr) {
            byte[] addrBytes = addr.getAddress();
            if (addrBytes.length != networkAddress.length) return false;
            int remaining = prefixLength;
            for (int i = 0; i < addrBytes.length && remaining > 0; i++) {
                int bits = Math.min(remaining, 8);
                int mask = 0xFF & (0xFF << (8 - bits));
                if ((addrBytes[i] & mask) != (networkAddress[i] & mask)) return false;
                remaining -= bits;
            }
            return true;
        }
    }
}
