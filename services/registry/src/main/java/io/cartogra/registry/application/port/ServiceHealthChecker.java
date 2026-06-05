package io.cartogra.registry.application.port;

import io.cartogra.registry.domain.ServiceHealthStatus;

public interface ServiceHealthChecker {
    ServiceHealthStatus check(String healthEndpoint);
}
