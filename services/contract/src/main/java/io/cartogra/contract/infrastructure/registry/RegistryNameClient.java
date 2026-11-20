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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Direct calls to Registry's internal {@code /internal/services/names} and {@code /internal/services/by-external-id};
 * failures propagate as {@code RestClientException}.
 */
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

    /** The live service with the SCM external id; empty when Registry gives 404. */
    public Optional<UUID> findServiceIdByExternalId(UUID tenantId, String externalId) {
        ServiceIdEnvelope envelope;
        try {
            envelope = retry.executeSupplier(() -> restClient.get()
                    .uri(uri -> uri.path("/internal/services/by-external-id")
                            .queryParam("tenantId", tenantId)
                            .queryParam("externalId", externalId)
                            .build())
                    .header("X-Tenant-Id", tenantId.toString())
                    .retrieve()
                    .body(ServiceIdEnvelope.class));
        } catch (HttpClientErrorException.NotFound _) {
            return Optional.empty();
        }
        return Optional.ofNullable(envelope).map(ServiceIdEnvelope::data).map(ServiceId::serviceId);
    }

    private record NamesRequest(UUID tenantId, Collection<UUID> serviceIds) {
    }

    private record NamesEnvelope(Map<UUID, String> data, String traceId) {
    }

    private record ServiceId(UUID serviceId) {
    }

    private record ServiceIdEnvelope(ServiceId data, String traceId) {
    }
}
