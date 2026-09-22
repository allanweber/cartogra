package io.cartogra.topology.infrastructure.registry;

import io.cartogra.common.api.PageResult;
import io.cartogra.topology.config.RegistryClientProperties;
import io.cartogra.web.client.ServiceCallRetry;
import io.cartogra.web.client.TraceparentRequestInterceptor;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Direct service-to-service call to Registry's internal cross-tenant services endpoint
 * (bypasses the Gateway — same trust model as ingestion's {@code RegistryPlanLimitClient}:
 * Registry's own SecurityConfig permits all traffic and relies on the ClusterIP network
 * boundary). Backs {@link io.cartogra.topology.domain.GraphNodeService#backfill()}.
 */
@Component
public class RegistryGraphNodeClient {

    private static final Logger log = LoggerFactory.getLogger(RegistryGraphNodeClient.class);

    private final RestClient restClient;
    private final Retry retry;

    public RegistryGraphNodeClient(RegistryClientProperties props,
            TraceparentRequestInterceptor traceparentRequestInterceptor) {
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestInterceptor(traceparentRequestInterceptor)
                .build();
        this.retry = ServiceCallRetry.threeAttempts("registry-graph-node-fetch", log);
    }

    /**
     * Bounded retry — 3 attempts, 1s fixed delay — matching {@code KafkaConfig}'s
     * {@code FixedBackOff(1000L, 3)}. Unlike {@code RegistryPlanLimitClient}, this does NOT
     * fail open: backfill has no sane "unlimited" default, so once attempts are exhausted
     * {@link Retry#executeSupplier} rethrows the last exception to the caller.
     */
    public List<RegistryServiceSnapshot> listActiveServices(int limit, int offset) {
        ServicesEnvelope envelope = retry.executeSupplier(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/services")
                        .queryParam("limit", limit)
                        .queryParam("offset", offset)
                        .build())
                .retrieve()
                .body(ServicesEnvelope.class));
        return envelope != null && envelope.data() != null ? envelope.data().items() : List.of();
    }

    private record ServicesEnvelope(PageResult<RegistryServiceSnapshot> data, String traceId) {
    }
}
