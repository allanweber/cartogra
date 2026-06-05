package io.cartogra.registry.infrastructure.http;

import io.cartogra.registry.application.port.ServiceHealthChecker;
import io.cartogra.registry.domain.ServiceHealthStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class RestClientHealthChecker implements ServiceHealthChecker {

    private final RestClient restClient;

    public RestClientHealthChecker(@Qualifier("healthProbeRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ServiceHealthStatus check(String healthEndpoint) {
        try {
            restClient.get()
                    .uri(healthEndpoint)
                    .retrieve()
                    .toBodilessEntity();
            return ServiceHealthStatus.HEALTHY;
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            // 401/403 signals the endpoint is reachable but requires credentials not yet configured.
            // Phase 2 task 2.12a will add per-service token storage and injection.
            if (status == 401 || status == 403) {
                return ServiceHealthStatus.PROBE_AUTH_FAILED;
            }
            return ServiceHealthStatus.DEGRADED;
        } catch (HttpServerErrorException e) {
            return ServiceHealthStatus.DEGRADED;
        } catch (ResourceAccessException e) {
            return ServiceHealthStatus.UNHEALTHY;
        }
    }
}
