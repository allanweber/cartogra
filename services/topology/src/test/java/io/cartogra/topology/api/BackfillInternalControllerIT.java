package io.cartogra.topology.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import io.cartogra.topology.repository.GraphNodeRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin backfill (issue [1.1] acceptance: "walks the registry once for tenants that
 * predate the consumer") — WireMock stands in for Registry's {@code /internal/services}
 * so this doesn't depend on a live registry instance. WireMock is started before the
 * Spring context (its port feeds {@code topology.registry.base-url}, bound once into
 * {@code RegistryGraphNodeClient}'s RestClient at context startup), mirroring how
 * {@link KafkaTestSupport}/{@link PostgresTestSupport} start their containers once per JVM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackfillInternalControllerIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final WireMockServer WIRE_MOCK =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @LocalServerPort
    int port;

    @Autowired
    GraphNodeRepository graphNodeRepository;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=topology");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
        registry.add("topology.registry.base-url", () -> "http://localhost:" + WIRE_MOCK.port());
        // Isolate this context's GraphNodeEventConsumer from every other topology
        // @SpringBootTest context sharing the hardcoded "topology-consumer" group id — see
        // GraphNodeEventConsumerIT for why.
        registry.add("spring.kafka.consumer.group-id", () -> "backfill-controller-it-" + UUID.randomUUID());
    }

    @Test
    void backfillPagesThroughRegistryAndSeedsGraphNodes() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID serviceA = UUID.randomUUID();
        UUID serviceB = UUID.randomUUID();

        WIRE_MOCK.stubFor(get(urlPathEqualTo("/internal/services"))
                .withQueryParam("offset", equalTo("0"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"items":[
                                  {"id":"%s","tenantId":"%s","name":"pre-existing-a","teamId":null,"tier":"STANDARD","healthStatus":"HEALTHY"},
                                  {"id":"%s","tenantId":"%s","name":"pre-existing-b","teamId":null,"tier":null,"healthStatus":"UNKNOWN"}
                                ],"total":2,"limit":200,"offset":0},"traceId":"a3f1c8d2000000000000000000000000"}
                                """.formatted(serviceA, tenantA, serviceB, tenantB))));

        WIRE_MOCK.stubFor(get(urlPathEqualTo("/internal/services"))
                .withQueryParam("offset", equalTo("200"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"items":[],"total":2,"limit":200,"offset":200},"traceId":"a3f1c8d2000000000000000000000000"}
                                """)));

        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/topology/internal/backfill"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"nodesUpserted\":2");

        assertThat(graphNodeRepository.findByServiceId(tenantA, serviceA)).isPresent();
        assertThat(graphNodeRepository.findByServiceId(tenantA, serviceA).get().name()).isEqualTo("pre-existing-a");
        assertThat(graphNodeRepository.findByServiceId(tenantB, serviceB)).isPresent();
        assertThat(graphNodeRepository.findByServiceId(tenantB, serviceB).get().tier()).isNull();
        assertThat(graphNodeRepository.findByServiceId(tenantB, serviceB).get().healthStatus()).isEqualTo("UNKNOWN");
    }
}
