package io.cartogra.contract.infrastructure.slack;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cartogra.contract.AbstractContractIT;
import io.cartogra.contract.domain.DeliveryAttempt;
import io.cartogra.contract.repository.DeliveryAttemptRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookNotificationSenderIT extends AbstractContractIT {

    private static final WireMockServer WIRE_MOCK =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    @Autowired
    private WebhookNotificationSender sender;

    @Autowired
    private DeliveryAttemptRepository logRepository;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
    void retriesBackoffAndWritesAttempts() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID recoveredRule = UUID.randomUUID();
        seedRule(tenant, recoveredRule);
        String url = "http://localhost:" + WIRE_MOCK.port() + "/hook/secret";
        String text = "BREAKING contract change for ledger-service\nChanges: 1\n";

        WIRE_MOCK.stubFor(post(urlPathEqualTo("/hook/secret"))
                .inScenario("webhook-retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("recovered"));
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/hook/secret"))
                .inScenario("webhook-retry")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));

        sender.send(tenant, recoveredRule, null, DeliveryAttempt.SLACK, url, text);

        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlPathEqualTo("/hook/secret")))).hasSize(2);
        for (var request : WIRE_MOCK.findAll(postRequestedFor(urlPathEqualTo("/hook/secret")))) {
            JsonNode body = objectMapper.readTree(request.getBodyAsString());
            assertThat(body.has("text")).isTrue();
            assertThat(body.get("text").stringValue()).isEqualTo(text);
            assertThat(body.has("blocks")).isFalse();
            assertThat(body.has("attachments")).isFalse();
        }
        List<DeliveryAttempt> recovered = logRepository.listByRule(tenant, recoveredRule, 50, 0);
        assertThat(recovered).hasSize(2);
        DeliveryAttempt first = recovered.stream().filter(row -> row.attempt() == 1).findFirst().orElseThrow();
        DeliveryAttempt second = recovered.stream().filter(row -> row.attempt() == 2).findFirst().orElseThrow();
        assertThat(first.waitMs()).isZero();
        assertThat(first.statusCode()).isEqualTo(429);
        assertThat(first.outcome()).isEqualTo(DeliveryAttempt.FAILED);
        assertThat(first.finishedAt()).isNotNull();
        assertThat(second.waitMs()).isEqualTo(1000);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.outcome()).isEqualTo(DeliveryAttempt.SENT);
        assertThat(second.finishedAt()).isNotNull();
        assertThat(recovered).allSatisfy(row -> {
            assertThat(row.channel()).isEqualTo(DeliveryAttempt.SLACK);
            assertThat(row.destinationHint()).isEqualTo("http://localhost:" + WIRE_MOCK.port() + "/hook/***");
            assertThat(row.destinationHint()).doesNotContain("secret");
            assertThat(row.checkId()).isNull();
        });

        WIRE_MOCK.resetAll();
        UUID exhaustedTenant = UUID.randomUUID();
        UUID exhaustedRule = UUID.randomUUID();
        seedRule(exhaustedTenant, exhaustedRule);
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/hook/secret")).willReturn(aResponse().withStatus(500)));

        sender.send(exhaustedTenant, exhaustedRule, null, DeliveryAttempt.TEAMS, url, text);

        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlPathEqualTo("/hook/secret")))).hasSize(3);
        for (var request : WIRE_MOCK.findAll(postRequestedFor(urlPathEqualTo("/hook/secret")))) {
            JsonNode body = objectMapper.readTree(request.getBodyAsString());
            assertThat(body.has("text")).isTrue();
            assertThat(body.has("blocks")).isFalse();
            assertThat(body.has("attachments")).isFalse();
        }
        List<DeliveryAttempt> exhausted = logRepository.listByRule(exhaustedTenant, exhaustedRule, 50, 0);
        assertThat(exhausted).hasSize(3);
        assertThat(exhausted).extracting(DeliveryAttempt::attempt).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(exhausted).extracting(DeliveryAttempt::waitMs).containsExactlyInAnyOrder(0, 1000, 2000);
        assertThat(exhausted).allSatisfy(row -> {
            assertThat(row.channel()).isEqualTo(DeliveryAttempt.TEAMS);
            assertThat(row.statusCode()).isEqualTo(500);
            assertThat(row.outcome()).isEqualTo(DeliveryAttempt.FAILED);
            assertThat(row.finishedAt()).isNotNull();
        });
    }

    @Test
    void unauthorizedResponseFailsWithoutRetry() {
        UUID tenant = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        seedRule(tenant, ruleId);
        String url = "http://localhost:" + WIRE_MOCK.port() + "/hook/secret";
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/hook/secret")).willReturn(aResponse().withStatus(401)));

        List<DeliveryAttempt> rows = sender.send(tenant, ruleId, null, DeliveryAttempt.SLACK, url, "test text");

        assertThat(rows).hasSize(1);
        DeliveryAttempt row = rows.get(0);
        assertThat(row.statusCode()).isEqualTo(401);
        assertThat(row.errorCode()).isEqualTo("HTTP_401");
        assertThat(row.outcome()).isEqualTo(DeliveryAttempt.FAILED);
        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlPathEqualTo("/hook/secret")))).hasSize(1);
        List<DeliveryAttempt> persisted = logRepository.listByRule(tenant, ruleId, 50, 0);
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).errorCode()).isEqualTo("HTTP_401");
    }

    private void seedRule(UUID tenant, UUID ruleId) {
        jdbc.update("INSERT INTO notification_rules (id, tenant_id) VALUES (:id, :t)",
                new MapSqlParameterSource("id", ruleId).addValue("t", tenant));
    }
}
