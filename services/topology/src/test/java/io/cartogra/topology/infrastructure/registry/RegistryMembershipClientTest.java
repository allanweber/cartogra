package io.cartogra.topology.infrastructure.registry;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cartogra.topology.config.RegistryClientProperties;
import io.cartogra.web.client.TraceparentRequestInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistryMembershipClientTest {

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

    private RegistryMembershipClient client() {
        return new RegistryMembershipClient(
                new RegistryClientProperties("http://localhost:" + WIRE_MOCK.port()),
                new TraceparentRequestInterceptor());
    }

    @Test
    void returnsMapFromRegistryResponse() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        WIRE_MOCK.stubFor(post(urlPathEqualTo("/internal/services/access"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"%s":true},"traceId":"a3f1c8d2000000000000000000000000"}
                                """.formatted(serviceId))));

        Map<UUID, Boolean> result = client().checkAccess(tenantId, userId, List.of(serviceId));

        assertThat(result).containsEntry(serviceId, true);
        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo("/internal/services/access")));
    }

    @Test
    void retriesUpTo3TimesOnTransientFailure_thenSucceeds() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        WIRE_MOCK.stubFor(post(urlPathEqualTo("/internal/services/access"))
                .inScenario("retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("second"));
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/internal/services/access"))
                .inScenario("retry")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"%s":false},"traceId":"a3f1c8d2000000000000000000000000"}
                                """.formatted(serviceId))));

        Map<UUID, Boolean> result = client().checkAccess(tenantId, userId, List.of(serviceId));

        assertThat(result).containsEntry(serviceId, false);
        WIRE_MOCK.verify(2, postRequestedFor(urlPathEqualTo("/internal/services/access")));
    }

    @Test
    void exhaustsRetries_thenPropagatesRestClientException() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        WIRE_MOCK.stubFor(post(urlPathEqualTo("/internal/services/access"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client().checkAccess(tenantId, userId, List.of(serviceId)))
                .isInstanceOf(RestClientException.class);

        WIRE_MOCK.verify(3, postRequestedFor(urlPathEqualTo("/internal/services/access")));
    }
}
