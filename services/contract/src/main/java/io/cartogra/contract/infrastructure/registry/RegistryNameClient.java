package io.cartogra.contract.infrastructure.registry;

import io.cartogra.contract.config.RegistryClientProperties;
import io.cartogra.web.client.ServiceCallRetry;
import io.cartogra.web.client.ServiceTokenRequestInterceptor;
import io.cartogra.web.client.TraceparentRequestInterceptor;
import io.cartogra.web.security.InternalTokenCodec;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Direct call to Registry's internal {@code /internal/services/names}; failures propagate as {@code RestClientException}. */
@Component
public class RegistryNameClient {

    private static final Logger log = LoggerFactory.getLogger(RegistryNameClient.class);

    private final RestClient restClient;
    private final Retry retry;

    public RegistryNameClient(RegistryClientProperties props,
                              TraceparentRequestInterceptor traceparentRequestInterceptor,
            InternalTokenCodec internalTokenCodec) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(props.timeout()).build());
        factory.setReadTimeout(props.timeout());
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .requestInterceptor(traceparentRequestInterceptor)
                .requestInterceptor(new ServiceTokenRequestInterceptor(internalTokenCodec, "contract", "registry"))
                .build();
        this.retry = ServiceCallRetry.threeAttempts("registry-service-names", log);
    }

    /** Names by service id; ids Registry does not know (or that are deleted) are absent. */
    public Map<UUID, String> findNames(UUID tenantId, Collection<UUID> serviceIds) {
        NamesEnvelope envelope = retry.executeSupplier(() -> restClient.post()
                .uri("/internal/services/names")
                .header("X-Tenant-Id", tenantId.toString())
                .body(new NamesRequest(tenantId, serviceIds))
                .retrieve()
                .body(NamesEnvelope.class));
        return envelope != null && envelope.data() != null ? envelope.data() : Map.of();
    }

    private record NamesRequest(UUID tenantId, Collection<UUID> serviceIds) {
    }

    private record NamesEnvelope(Map<UUID, String> data, String traceId) {
    }
}
