package io.cartogra.topology.infrastructure.registry;

import io.cartogra.topology.config.RegistryClientProperties;
import io.cartogra.web.client.ServiceCallRetry;
import io.cartogra.web.client.TraceparentRequestInterceptor;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Direct service-to-service call to Registry's internal membership-check endpoint (bypasses
 * the Gateway, same trust model as {@link RegistryGraphNodeClient}). Unlike
 * {@code RegistryPlanLimitClient}'s fail-open pattern, this fails closed: a propagated
 * {@link RestClientException} must be treated as "deny", not "allow".
 */
@Component
public class RegistryMembershipClient {

    private static final Logger log = LoggerFactory.getLogger(RegistryMembershipClient.class);

    private final RestClient restClient;
    private final Retry retry;

    public RegistryMembershipClient(RegistryClientProperties props,
            TraceparentRequestInterceptor traceparentRequestInterceptor) {
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestInterceptor(traceparentRequestInterceptor)
                .build();
        this.retry = ServiceCallRetry.threeAttempts("registry-membership-check", log);
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
