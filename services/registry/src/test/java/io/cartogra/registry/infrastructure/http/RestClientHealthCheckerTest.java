package io.cartogra.registry.infrastructure.http;

import io.cartogra.registry.domain.ServiceHealthStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class RestClientHealthCheckerTest {

    private MockRestServiceServer mockServer;
    private RestClientHealthChecker checker;

    @BeforeEach
    void setUp() {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        var builder = RestClient.builder().requestFactory(factory);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        checker = new RestClientHealthChecker(builder.build());
    }

    @Test
    void http200ReturnsHealthy() {
        mockServer.expect(requestTo("http://example.com/health"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.OK));

        assertThat(checker.check("http://example.com/health")).isEqualTo(ServiceHealthStatus.HEALTHY);
        mockServer.verify();
    }

    @Test
    void http401ReturnsProbeAuthFailed() {
        mockServer.expect(requestTo("http://example.com/health"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(checker.check("http://example.com/health")).isEqualTo(ServiceHealthStatus.PROBE_AUTH_FAILED);
    }

    @Test
    void http403ReturnsProbeAuthFailed() {
        mockServer.expect(requestTo("http://example.com/health"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(checker.check("http://example.com/health")).isEqualTo(ServiceHealthStatus.PROBE_AUTH_FAILED);
    }

    @Test
    void http503ReturnsDegraded() {
        mockServer.expect(requestTo("http://example.com/health"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(checker.check("http://example.com/health")).isEqualTo(ServiceHealthStatus.DEGRADED);
    }

    @Test
    void http404ReturnsDegraded() {
        mockServer.expect(requestTo("http://example.com/health"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(checker.check("http://example.com/health")).isEqualTo(ServiceHealthStatus.DEGRADED);
    }

    @Test
    void http500ReturnsDegraded() {
        mockServer.expect(requestTo("http://example.com/health"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(checker.check("http://example.com/health")).isEqualTo(ServiceHealthStatus.DEGRADED);
    }
}
