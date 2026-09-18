package io.cartogra.topology.infrastructure.registry;

import io.cartogra.common.api.PageResult;
import io.cartogra.topology.config.RegistryClientProperties;
import io.cartogra.web.client.TraceparentRequestInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * Direct service-to-service call to Registry's internal cross-tenant services endpoint
 * (bypasses the Gateway — same trust model as ingestion's {@code RegistryPlanLimitClient}:
 * Registry's own SecurityConfig permits all traffic and relies on the ClusterIP network
 * boundary). Backs {@link io.cartogra.topology.domain.GraphNodeService#backfill()}.
 */
@Component
public class RegistryGraphNodeClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MILLIS = 1000L;

    private static final Logger log = LoggerFactory.getLogger(RegistryGraphNodeClient.class);

    private final RestClient restClient;

    public RegistryGraphNodeClient(RegistryClientProperties props,
            TraceparentRequestInterceptor traceparentRequestInterceptor) {
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestInterceptor(traceparentRequestInterceptor)
                .build();
    }

    /**
     * Bounded retry — 3 attempts, 1s fixed delay — matching {@code KafkaConfig}'s
     * {@code FixedBackOff(1000L, 3)}. Unlike {@code RegistryPlanLimitClient}, this does NOT
     * fail open: backfill has no sane "unlimited" default, so once attempts are exhausted the
     * last exception propagates to the caller.
     */
    public List<RegistryServiceSnapshot> listActiveServices(int limit, int offset) {
        RestClientException lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ServicesEnvelope envelope = restClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/internal/services")
                                .queryParam("limit", limit)
                                .queryParam("offset", offset)
                                .build())
                        .retrieve()
                        .body(ServicesEnvelope.class);
                return envelope != null && envelope.data() != null ? envelope.data().items() : List.of();
            } catch (RestClientException e) {
                lastException = e;
                log.warn("Attempt {}/{} to fetch registry services (offset={}) failed: {}",
                        attempt, MAX_ATTEMPTS, offset, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleep(RETRY_DELAY_MILLIS);
                }
            }
        }
        throw lastException;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying registry call", e);
        }
    }

    private record ServicesEnvelope(PageResult<RegistryServiceSnapshot> data, String traceId) {
    }
}
