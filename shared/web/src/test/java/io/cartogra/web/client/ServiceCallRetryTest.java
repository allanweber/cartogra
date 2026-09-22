package io.cartogra.web.client;

import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceCallRetryTest {

    private static final Logger log = LoggerFactory.getLogger(ServiceCallRetryTest.class);

    @Test
    void retriesOn5xxThenSucceeds() {
        Retry retry = ServiceCallRetry.threeAttempts("test-5xx", log);
        AtomicInteger attempts = new AtomicInteger();

        String result = retry.executeSupplier(() -> {
            if (attempts.incrementAndGet() < 2) {
                throw HttpServerErrorException.create(HttpStatusCode.valueOf(503), "Service Unavailable",
                        null, null, null);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void retriesOnConnectionFailureThenSucceeds() {
        Retry retry = ServiceCallRetry.threeAttempts("test-connection", log);
        AtomicInteger attempts = new AtomicInteger();

        String result = retry.executeSupplier(() -> {
            if (attempts.incrementAndGet() < 2) {
                throw new ResourceAccessException("Connection refused");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void doesNotRetryOn4xx() {
        Retry retry = ServiceCallRetry.threeAttempts("test-4xx", log);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.executeSupplier(() -> {
            attempts.incrementAndGet();
            throw HttpClientErrorException.create(HttpStatusCode.valueOf(404), "Not Found",
                    null, null, null);
        })).isInstanceOf(HttpClientErrorException.class);

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void exhaustsAllAttemptsOnPersistent5xx() {
        Retry retry = ServiceCallRetry.threeAttempts("test-exhausted", log);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.executeSupplier(() -> {
            attempts.incrementAndGet();
            throw HttpServerErrorException.create(HttpStatusCode.valueOf(500), "Internal Server Error",
                    null, null, null);
        })).isInstanceOf(HttpServerErrorException.class);

        assertThat(attempts.get()).isEqualTo(3);
    }
}
