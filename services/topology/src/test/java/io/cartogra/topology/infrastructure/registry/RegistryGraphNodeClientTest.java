package io.cartogra.topology.infrastructure.registry;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cartogra.topology.config.RegistryClientProperties;
import io.cartogra.web.client.TraceparentRequestInterceptor;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

class RegistryGraphNodeClientTest {

    private static final WireMockServer WIRE_MOCK =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    @BeforeAll
    static void startWireMock() {
        WIRE_MOCK.start();
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @AfterEach
    void resetWireMock() {
        WIRE_MOCK.resetAll();
    }

    @Test
    void listActiveServicesSendsTraceparentHeader() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/internal/services"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"items":[],"total":0,"limit":200,"offset":0},"traceId":"a3f1c8d2000000000000000000000000"}
                                """)));

        var client = new RegistryGraphNodeClient(
                new RegistryClientProperties("http://localhost:" + WIRE_MOCK.port(), Duration.ofSeconds(3)),
                new TraceparentRequestInterceptor());

        SpanContext spanContext = SpanContext.create(
                "0123456789abcdef0123456789abcdef", "0123456789abcdef",
                TraceFlags.getSampled(), TraceState.getDefault());
        try (var ignored = Context.root()
                .with(Span.wrap(spanContext))
                .makeCurrent()) {
            client.listActiveServices(200, 0);
        }

        WIRE_MOCK.verify(getRequestedFor(urlPathEqualTo("/internal/services"))
                .withHeader("traceparent", matching("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")));
    }
}
