package io.cartogra.gateway.infrastructure.health;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports each gateway circuit breaker's state as a health detail without ever contributing a
 * non-UP status: an OPEN breaker means the gateway is correctly protecting itself from a
 * struggling downstream, not that the gateway itself is unhealthy. Letting an open breaker fail
 * k8s readiness would pull the gateway out of the load balancer and make the incident worse.
 */
@Component("circuitBreakersHealthIndicator")
public class CircuitBreakersHealthIndicator implements HealthIndicator {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public CircuitBreakersHealthIndicator(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        for (CircuitBreaker circuitBreaker : circuitBreakerRegistry.getAllCircuitBreakers()) {
            Map<String, Object> breakerDetails = new LinkedHashMap<>();
            breakerDetails.put("circuitBreakerState", circuitBreaker.getState().name());
            breakerDetails.put("failureRate", circuitBreaker.getMetrics().getFailureRate());
            breakerDetails.put("bufferedCalls", circuitBreaker.getMetrics().getNumberOfBufferedCalls());
            details.put(circuitBreaker.getName(), breakerDetails);
        }
        return Health.up().withDetails(details).build();
    }
}
