package io.cartogra.contract.infrastructure.registry;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cartogra.contract.config.RegistryClientProperties;
import io.cartogra.web.client.TraceparentRequestInterceptor;
import io.cartogra.web.security.InternalTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import java.time.Clock;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistryNameClientTest {

    private static final WireMockServer WIRE_MOCK =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    private static final String PATH = "/internal/services/names";

    @BeforeAll
    static void start() {
        WIRE_MOCK.start();
    }

    @AfterAll
    static void stop() {
        WIRE_MOCK.stop();
    }

    @AfterEach
    void reset() {
        WIRE_MOCK.resetAll();
    }

    private RegistryNameClient client() {
        return new RegistryNameClient(
                new RegistryClientProperties("http://localhost:" + WIRE_MOCK.port(), Duration.ofSeconds(3)),
                new TraceparentRequestInterceptor(),
                InternalTokens.codec(Clock.systemUTC()));
    }

    @Test
    void sendsAllIdsInOneRequestAndReadsTheEnvelope() {
        UUID tenant = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        WIRE_MOCK.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"data":{"%s":"ledger"},"traceId":"a3f1c8d2000000000000000000000000"}
                        """.formatted(a))));

        assertThat(client().findNames(tenant, List.of(a, b))).containsExactlyEntriesOf(Map.of(a, "ledger"));
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(PATH))
                .withHeader("X-Tenant-Id", equalTo(tenant.toString()))
                .withHeader(InternalTokens.HEADER, matching("[^.]+\\.[^.]+\\.[^.]+"))
                .withRequestBody(equalToJson("""
                {"tenantId":"%s","serviceIds":["%s","%s"]}
                """.formatted(tenant, a, b))));
    }

    @Test
    void propagatesAfterThreeFailedAttempts() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client().findNames(UUID.randomUUID(), List.of(UUID.randomUUID())))
                .isInstanceOf(RestClientException.class);
        WIRE_MOCK.verify(3, postRequestedFor(urlPathEqualTo(PATH)));
    }
}
