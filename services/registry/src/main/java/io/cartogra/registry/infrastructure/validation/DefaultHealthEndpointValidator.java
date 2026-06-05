package io.cartogra.registry.infrastructure.validation;

import io.cartogra.registry.application.port.HealthEndpointValidator;
import io.cartogra.registry.domain.exception.InvalidHealthEndpointException;
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

@Component
public class DefaultHealthEndpointValidator implements HealthEndpointValidator {

    private static final Logger logger = LoggerFactory.getLogger(DefaultHealthEndpointValidator.class);

    private final boolean allowHttp;
    private final boolean allowPrivateEndpoints;
    private final List<CidrBlock> allowedCidrs;

    public DefaultHealthEndpointValidator(
            @Value("${registry.health.allow-http-endpoints:false}") boolean allowHttp,
            @Value("${registry.health.allow-private-endpoints:false}") boolean allowPrivateEndpoints,
            @Value("${registry.health.allowed-cidrs:}") String allowedCidrsRaw) {
        this.allowHttp = allowHttp;
        this.allowPrivateEndpoints = allowPrivateEndpoints;
        this.allowedCidrs = parseCidrs(allowedCidrsRaw);
    }

    @Override
    public void validate(String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidHealthEndpointException(url, "URL must not be blank");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new InvalidHealthEndpointException(url, "Unparseable URL");
        }

        String scheme = uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new InvalidHealthEndpointException(url, "Only http and https schemes are permitted");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidHealthEndpointException(url, "URL has no host");
        }

        // Resolve hostname before scheme enforcement so that private/loopback addresses are
        // rejected with an informative SSRF message even when http is also disallowed.
        // DNS rebinding after write time is an accepted residual risk — the probe is a read-only
        // GET with a short timeout, so the blast radius of a successful rebinding attack is negligible.
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            logger.warn("Cannot resolve health endpoint host '{}': {}", host, e.getMessage());
            throw new InvalidHealthEndpointException(url, "Cannot resolve host: " + host);
        }

        for (InetAddress addr : addresses) {
            if (isHardBlocked(addr)) {
                throw new InvalidHealthEndpointException(url, "Host resolves to a loopback or link-local address");
            }
            if (isSoftBlocked(addr) && !allowPrivateEndpoints && !isInAllowedCidr(addr)) {
                throw new InvalidHealthEndpointException(
                        url,
                        "Host resolves to a private address; set registry.health.allow-private-endpoints=true or add the CIDR to registry.health.allowed-cidrs");
            }
        }

        if ("http".equals(scheme) && !allowHttp) {
            throw new InvalidHealthEndpointException(url, "HTTP endpoints are not allowed in production; use HTTPS or set registry.health.allow-http-endpoints=true");
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
