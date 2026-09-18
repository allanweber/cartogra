package io.cartogra.web.client;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Injects the current OTel span's W3C {@code traceparent} header onto an outbound
 * {@code RestClient} request — the same propagation every Kafka producer and Gateway route
 * already performs. Not a {@code @Component}: cross-module component scanning isn't reliable
 * across service boundaries (each service's {@code @SpringBootApplication} only scans its own
 * package tree), so consumers declare it as a {@code @Bean} explicitly and attach it via
 * {@code RestClient.Builder#requestInterceptor}.
 *
 * <p>Attach this only to internal service-to-service clients (Registry, and future
 * Contract/Intelligence clients). External provider clients (SCM, OAuth, email) should not
 * leak internal trace context to third parties.
 */
public class TraceparentRequestInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        W3CTraceContextPropagator.getInstance()
                .inject(Context.current(), request.getHeaders(), (headers, key, value) -> headers.add(key, value));
        return execution.execute(request, body);
    }
}
