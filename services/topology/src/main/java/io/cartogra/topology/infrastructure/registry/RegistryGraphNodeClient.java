package io.cartogra.topology.infrastructure.registry;

import io.cartogra.common.api.PageResult;
import io.cartogra.topology.config.RegistryClientProperties;
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

    private final RestClient restClient;

    public RegistryGraphNodeClient(RegistryClientProperties props) {
        this.restClient = RestClient.builder().baseUrl(props.baseUrl()).build();
    }

    public List<RegistryServiceSnapshot> listActiveServices(int limit, int offset) {
        ServicesEnvelope envelope = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/services")
                        .queryParam("limit", limit)
                        .queryParam("offset", offset)
                        .build())
                .retrieve()
                .body(ServicesEnvelope.class);
        return envelope != null && envelope.data() != null ? envelope.data().items() : List.of();
    }

    private record ServicesEnvelope(PageResult<RegistryServiceSnapshot> data, String traceId) {
    }
}
