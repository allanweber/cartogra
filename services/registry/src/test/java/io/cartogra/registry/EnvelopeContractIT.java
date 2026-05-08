package io.cartogra.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cartogra.test.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class EnvelopeContractIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Test
    void pingReturnsEnvelopeAndTraceIdHeader() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/ping"))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        // Verify status and envelope structure
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"data\"");
        assertThat(response.body()).contains("\"traceId\"");

        // Verify X-Trace-Id header is present and non-blank
        assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();
        String traceIdHeader = response.headers().firstValue("X-Trace-Id").get();
        assertThat(traceIdHeader).isNotBlank();

        // Verify traceId matches 32 lowercase hex chars
        assertThat(traceIdHeader).matches("[0-9a-f]{32}");

        // Verify body traceId equals header value
        JsonNode responseBody = MAPPER.readTree(response.body());
        String traceIdBody = responseBody.get("traceId").asText();
        assertThat(traceIdBody).isEqualTo(traceIdHeader);
    }

    @Test
    void pingPropagatesIncomingTraceparent() throws Exception {
        // Known test trace ID (32 hex chars)
        String testTraceId = "0af7651916cd43dd8448eb211c80319c";
        String testSpanId = "b7ad6b7169203331";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/ping"))
                .header("traceparent", String.format("00-%s-%s-01", testTraceId, testSpanId))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        // Verify status
        assertThat(response.statusCode()).isEqualTo(200);

        // Verify the incoming trace ID was propagated to the response
        String traceIdHeader = response.headers().firstValue("X-Trace-Id").get();
        assertThat(traceIdHeader).isEqualTo(testTraceId);

        // Verify body traceId matches
        JsonNode responseBody = MAPPER.readTree(response.body());
        String traceIdBody = responseBody.get("traceId").asText();
        assertThat(traceIdBody).isEqualTo(testTraceId);
    }
}
