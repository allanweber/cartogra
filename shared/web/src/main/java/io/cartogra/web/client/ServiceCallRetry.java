package io.cartogra.web.client;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * Shared bounded-retry policy for direct service-to-service {@code RestClient} calls (Registry
 * lookups, etc.) — one place to change the policy instead of three near-identical copies.
 * 3 attempts, 1s fixed delay, logs a WARN on every retry (not just on final exhaustion) so
 * transient flakiness is visible in logs before it escalates to a caller-visible failure.
 *
 * <p>Only retries transient failures: 5xx responses and non-HTTP-status errors (connection
 * refused, timeouts). A 4xx {@link HttpStatusCodeException} is a client error that will never
 * succeed on retry, so it fails immediately instead of burning attempts and latency on a
 * call that can't work.
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
