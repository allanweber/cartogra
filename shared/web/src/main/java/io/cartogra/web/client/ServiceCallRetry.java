package io.cartogra.web.client;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * Shared bounded-retry policy for direct service-to-service {@code RestClient} calls: 3
 * attempts, 1s fixed delay, retries only transient failures (5xx, connection errors) — a 4xx
 * {@link HttpStatusCodeException} fails immediately since retrying it can never succeed.
 */
public final class ServiceCallRetry {

    private ServiceCallRetry() {
    }

    public static Retry threeAttempts(String name, Logger log) {
        Retry retry = Retry.of(name, RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofSeconds(1))
                .retryOnException(ServiceCallRetry::isTransient)
                .build());
        retry.getEventPublisher().onRetry(event -> log.warn(event.toString()));
        return retry;
    }

    private static boolean isTransient(Throwable t) {
        if (t instanceof HttpStatusCodeException e) {
            return e.getStatusCode().is5xxServerError();
        }
        return t instanceof RestClientException;
    }
}
