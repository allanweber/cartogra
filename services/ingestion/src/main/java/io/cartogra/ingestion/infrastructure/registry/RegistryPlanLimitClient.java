package io.cartogra.ingestion.infrastructure.registry;

import io.cartogra.ingestion.config.RegistryClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.UUID;

/**
 * Direct service-to-service call to registry's internal plan-limits endpoint (bypasses the
 * Gateway — see ADR discussion: registry's own SecurityConfig permits all traffic and relies
 * on the ClusterIP network boundary, so this call carries the same trust level as any other
 * request registry accepts today).
 */
@Component
public class RegistryPlanLimitClient {

    private static final Logger log = LoggerFactory.getLogger(RegistryPlanLimitClient.class);

    private final RestClient restClient;

    public RegistryPlanLimitClient(RegistryClientProperties props) {
        this.restClient = RestClient.builder().baseUrl(props.baseUrl()).build();
    }

    /**
     * Empty when registry is unreachable — callers should fail open (treat as unlimited)
     * rather than block a legitimate write because of a transient internal-call failure.
     */
    public Optional<RegistryPlanLimits> fetchLimits(UUID tenantId) {
        try {
            PlanLimitsEnvelope envelope = restClient.get()
                    .uri("/internal/plan-limits/{tenantId}", tenantId)
                    .retrieve()
                    .body(PlanLimitsEnvelope.class);
            return Optional.ofNullable(envelope).map(PlanLimitsEnvelope::data);
        } catch (RestClientException e) {
            log.warn("Failed to fetch plan limits for tenant {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    private record PlanLimitsEnvelope(RegistryPlanLimits data, String traceId) {}
}
