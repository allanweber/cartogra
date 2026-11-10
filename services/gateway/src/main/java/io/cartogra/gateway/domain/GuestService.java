package io.cartogra.gateway.domain;

import io.cartogra.common.identity.Roles;
import io.cartogra.gateway.api.dto.TokenResponse;
import io.cartogra.gateway.config.GuestProperties;
import io.cartogra.gateway.domain.exception.NotFoundException;
import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import io.cartogra.gateway.infrastructure.jwt.JwtTokenProvider;
import io.cartogra.gateway.repository.TenantRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GuestService {

    private static final Logger log = LoggerFactory.getLogger(GuestService.class);

    private final GuestProperties props;
    private final JwtTokenProvider jwtTokenProvider;
    private final TenantRepository tenantRepository;

    /** Guest token mints, tagged {@code outcome=issued|rate_limited}; a spike means someone is scraping the demo. */
    public static final String MINTS_METRIC = "cartogra.guest.mints";

    private final Counter issued;

    public GuestService(GuestProperties props, JwtTokenProvider jwtTokenProvider,
                        TenantRepository tenantRepository, MeterRegistry meterRegistry) {
        this.props = props;
        this.jwtTokenProvider = jwtTokenProvider;
        this.tenantRepository = tenantRepository;
        this.issued = meterRegistry.counter(MINTS_METRIC, "outcome", "issued");
    }

    /**
     * Fails closed: a guest-configured tenant that was not created as a public sandbox stays private, so a wrong
     * id in config can never open a real tenant. Read on every call, so clearing the flag stops new mints at once.
     */
    public boolean enabled() {
        if (!props.enabled()) {
            return false;
        }
        boolean sandbox = tenantRepository.findByTenantId(props.tenantId()).map(Tenant::publicSandbox).orElse(false);
        if (!sandbox) {
            log.error("guest.tenant_not_public_sandbox tenantId={}, guest mode stays off", props.tenantId());
        }
        return sandbox;
    }

    /** A fresh, unpersisted identity per mint: no user row, no refresh token; re-mint on expiry. */
    public TokenResponse mint() {
        if (!enabled()) {
            throw new NotFoundException("Guest demo is not enabled");
        }
        UUID tenantId = props.tenantId();
        long ttlSeconds = props.tokenTtl().toSeconds();
        JwtClaims claims = new JwtClaims(UUID.randomUUID(), tenantId, null, "Demo guest", "guest",
            List.of(Roles.GUEST), Instant.now().plusSeconds(ttlSeconds));
        issued.increment();
        return new TokenResponse(jwtTokenProvider.issueAccessToken(claims), null, ttlSeconds);
    }
}
