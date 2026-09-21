package io.cartogra.topology.infrastructure.registry;

import io.cartogra.topology.config.RegistryClientProperties;
import io.cartogra.web.client.TraceparentRequestInterceptor;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Direct service-to-service call to Registry's internal membership-check endpoint (bypasses
 * the Gateway — same trust model as {@link RegistryGraphNodeClient}). Backs
 * {@code DependencyService}'s per-service authorization check for declared-dependency mutations.
 *
 * <p>Unlike {@code RegistryPlanLimitClient}'s fail-open pattern, this does NOT catch and fall
 * back on failure: an authorization check that silently defaults to "allow" when Registry is
 * unreachable would be a security hole, not a graceful degradation. Callers must treat a
 * propagated {@link RestClientException} as "deny" ({@link Retry#executeSupplier} rethrows the
 * last exception once attempts are exhausted) — mapped to 503 by {@code GlobalExceptionHandler}.
 */
@Component
public class RegistryMembershipClient {

    private final RestClient restClient;
    private final Retry retry;

    public RegistryMembershipClient(RegistryClientProperties props,
            TraceparentRequestInterceptor traceparentRequestInterceptor) {
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestInterceptor(traceparentRequestInterceptor)
                .build();
        this.retry = Retry.of("registry-membership-check", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofSeconds(1))
                .retryExceptions(RestClientException.class)
                .build());
    }

    /**
     * Returns, per requested serviceId, whether {@code userId} is a member of the team owning
     * it (tenant-scoped, resolved server-side by Registry).
     */
    public Map<UUID, Boolean> checkAccess(UUID tenantId, UUID userId, List<UUID> serviceIds) {
        AccessEnvelope envelope = retry.executeSupplier(() -> restClient.post()
                .uri("/internal/services/access")
                .body(new AccessRequest(tenantId, userId, serviceIds))
                .retrieve()
                .body(AccessEnvelope.class));
        return envelope != null && envelope.data() != null ? envelope.data() : Map.of();
    }

    private record AccessRequest(UUID tenantId, UUID userId, List<UUID> serviceIds) {
    }

    private record AccessEnvelope(Map<UUID, Boolean> data, String traceId) {
    }
}
