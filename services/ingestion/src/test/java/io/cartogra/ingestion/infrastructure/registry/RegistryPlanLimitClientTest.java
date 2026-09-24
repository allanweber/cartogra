package io.cartogra.ingestion.infrastructure.registry;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cartogra.ingestion.config.RegistryClientProperties;
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

import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

class RegistryPlanLimitClientTest {

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
    void fetchLimitsSendsTraceparentHeader() {
        WIRE_MOCK.stubFor(get(urlPathMatching("/internal/plan-limits/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"maxScmConnections":5,"maxK8sClusters":3},"traceId":"a3f1c8d2000000000000000000000000"}
                                """)));

        var client = new RegistryPlanLimitClient(
                new RegistryClientProperties("http://localhost:" + WIRE_MOCK.port()),
                new TraceparentRequestInterceptor());

        SpanContext spanContext = SpanContext.create(
                "0123456789abcdef0123456789abcdef", "0123456789abcdef",
                TraceFlags.getSampled(), TraceState.getDefault());
        Optional<RegistryPlanLimits> limits;
        try (var ignored = Context.root()
                .with(Span.wrap(spanContext))
                .makeCurrent()) {
            limits = client.fetchLimits(UUID.randomUUID());
        }

        assertThat(limits).contains(new RegistryPlanLimits(5, 3));
        WIRE_MOCK.verify(getRequestedFor(urlPathMatching("/internal/plan-limits/.*"))
                .withHeader("traceparent", matching("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")));
    }
}
