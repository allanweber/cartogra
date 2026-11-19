package io.cartogra.ingestion.infrastructure.registry;

import io.cartogra.ingestion.config.RegistryClientProperties;
import io.cartogra.web.client.ServiceCallRetry;
import io.cartogra.web.client.ServiceTokenRequestInterceptor;
import io.cartogra.web.client.TraceparentRequestInterceptor;
import io.cartogra.web.security.InternalTokenCodec;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.UUID;

/**
 * Direct service-to-service call to registry's internal plan-limits endpoint (bypasses the
 * Gateway). Each call carries a signed {@code SERVICE} token for the tenant of the call.
 */
@Component
public class RegistryPlanLimitClient {

    private static final Logger log = LoggerFactory.getLogger(RegistryPlanLimitClient.class);

    private final RestClient restClient;
    private final Retry retry;

    public RegistryPlanLimitClient(RegistryClientProperties props,
            TraceparentRequestInterceptor traceparentRequestInterceptor,
            InternalTokenCodec internalTokenCodec) {
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestInterceptor(traceparentRequestInterceptor)
                .requestInterceptor(new ServiceTokenRequestInterceptor(internalTokenCodec, "ingestion", "registry"))
                .build();
        this.retry = ServiceCallRetry.threeAttempts("registry-plan-limits-fetch", log);
    }

    /**
     * Empty when registry is unreachable after retrying — callers should fail open (treat as
     * unlimited) rather than block a legitimate write because of a transient internal-call failure.
     */
    public Optional<RegistryPlanLimits> fetchLimits(UUID tenantId) {
        try {
            PlanLimitsEnvelope envelope = retry.executeSupplier(() -> restClient.get()
                    .uri("/internal/plan-limits/{tenantId}", tenantId)
                .header("X-Tenant-Id", tenantId.toString())
                    .retrieve()
                    .body(PlanLimitsEnvelope.class));
            return Optional.ofNullable(envelope).map(PlanLimitsEnvelope::data);
        } catch (RestClientException e) {
            log.warn("Failed to fetch plan limits for tenant {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    private record PlanLimitsEnvelope(RegistryPlanLimits data, String traceId) {}
}
