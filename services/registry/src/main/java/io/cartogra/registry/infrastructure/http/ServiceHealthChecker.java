package io.cartogra.registry.infrastructure.http;

import io.cartogra.registry.domain.ServiceHealthStatus;

public interface ServiceHealthChecker {
    ServiceHealthStatus check(String healthEndpoint);
}
