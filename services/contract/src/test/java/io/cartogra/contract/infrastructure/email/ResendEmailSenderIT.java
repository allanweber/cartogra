package io.cartogra.contract.infrastructure.email;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

class ResendEmailSenderIT extends AbstractContractIT {

    private static final String API_KEY = "test-resend-key";
    private static final String FROM = "notifications@cartogra.dev";

    private static final WireMockServer WIRE_MOCK =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    @Autowired
    private ResendEmailSender sender;

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

    @DynamicPropertySource
    static void resendProps(DynamicPropertyRegistry registry) {
        registry.add("contract.resend.base-url", () -> "http://localhost:" + WIRE_MOCK.port());
        registry.add("contract.resend.api-key", () -> API_KEY);
        registry.add("contract.resend.from-address", () -> FROM);
    }

    @Test
    void retriesBackoffAndWritesAttempts() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        seedRule(tenant, ruleId);
        String first = "alice@example.com";
        String second = "bob@example.com";
        String text = "BREAKING contract change for ledger-service\nChanges: 1\n";

        WIRE_MOCK.stubFor(post(urlEqualTo("/emails"))
                .withRequestBody(matchingJsonPath("$.to[0]", equalTo(first)))
                .inScenario("email-retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("recovered"));
        WIRE_MOCK.stubFor(post(urlEqualTo("/emails"))
                .withRequestBody(matchingJsonPath("$.to[0]", equalTo(first)))
                .inScenario("email-retry")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));
        WIRE_MOCK.stubFor(post(urlEqualTo("/emails"))
                .withRequestBody(matchingJsonPath("$.to[0]", equalTo(second)))
                .willReturn(aResponse().withStatus(200)));

        sender.send(tenant, ruleId, null, first, text);
        sender.send(tenant, ruleId, null, second, text);

        var requests = WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/emails")));
        assertThat(requests).hasSize(3);
        assertThat(requests).allSatisfy(request ->
                assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + API_KEY));
        for (var request : requests) {
            JsonNode body = objectMapper.readTree(request.getBodyAsString());
            assertThat(body.get("from").stringValue()).isEqualTo(FROM);
            assertThat(body.get("subject").stringValue()).isEqualTo("Breaking contract change");
            assertThat(body.get("text").stringValue()).isEqualTo(text);
        }
        assertThat(requests).extracting(request -> objectMapper.readTree(request.getBodyAsString())
                        .get("to").get(0).stringValue())
                .containsExactlyInAnyOrder(first, first, second);

        List<DeliveryAttempt> sentRows = logRepository.listByRule(tenant, ruleId, 50, 0);
        assertThat(sentRows).hasSize(3);
        DeliveryAttempt firstTry = sentRows.stream().filter(row -> row.destinationHint().equals("a***@example.com")
                && row.attempt() == 1).findFirst().orElseThrow();
        DeliveryAttempt secondTry = sentRows.stream().filter(row -> row.destinationHint().equals("a***@example.com")
                && row.attempt() == 2).findFirst().orElseThrow();
        DeliveryAttempt otherAddress = sentRows.stream().filter(row -> row.destinationHint().equals("b***@example.com"))
                .findFirst().orElseThrow();
        assertThat(firstTry.waitMs()).isZero();
        assertThat(firstTry.statusCode()).isEqualTo(429);
        assertThat(firstTry.outcome()).isEqualTo(DeliveryAttempt.FAILED);
        assertThat(secondTry.waitMs()).isEqualTo(1000);
        assertThat(secondTry.statusCode()).isEqualTo(200);
        assertThat(secondTry.outcome()).isEqualTo(DeliveryAttempt.SENT);
        assertThat(otherAddress.waitMs()).isZero();
        assertThat(otherAddress.statusCode()).isEqualTo(200);
        assertThat(otherAddress.outcome()).isEqualTo(DeliveryAttempt.SENT);
        assertThat(sentRows).allSatisfy(row -> {
            assertThat(row.channel()).isEqualTo(DeliveryAttempt.EMAIL);
            assertThat(row.destinationHint()).doesNotContain("alice", "bob");
            assertThat(row.finishedAt()).isNotNull();
        });

        WIRE_MOCK.resetAll();
        UUID failedTenant = UUID.randomUUID();
        UUID failedRule = UUID.randomUUID();
        seedRule(failedTenant, failedRule);
        String failing = "carol@example.com";
        WIRE_MOCK.stubFor(post(urlEqualTo("/emails"))
                .withRequestBody(matchingJsonPath("$.to[0]", equalTo(failing)))
                .willReturn(aResponse().withStatus(500)));

        sender.send(failedTenant, failedRule, null, failing, text);

        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/emails")))).hasSize(3);
        List<DeliveryAttempt> failedRows = logRepository.listByRule(failedTenant, failedRule, 50, 0);
        assertThat(failedRows).hasSize(3);
        assertThat(failedRows).extracting(DeliveryAttempt::waitMs).containsExactlyInAnyOrder(0, 1000, 2000);
        assertThat(failedRows).allSatisfy(row -> {
            assertThat(row.channel()).isEqualTo(DeliveryAttempt.EMAIL);
            assertThat(row.statusCode()).isEqualTo(500);
            assertThat(row.outcome()).isEqualTo(DeliveryAttempt.FAILED);
            assertThat(row.destinationHint()).isEqualTo("c***@example.com");
            assertThat(row.finishedAt()).isNotNull();
        });
    }

    @Test
    void unauthorizedResponseFailsWithoutRetry() {
        UUID tenant = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        seedRule(tenant, ruleId);
        String email = "alice@example.com";
        WIRE_MOCK.stubFor(post(urlEqualTo("/emails")).willReturn(aResponse().withStatus(401)));

        List<DeliveryAttempt> rows = sender.send(tenant, ruleId, null, email, "test text");

        assertThat(rows).hasSize(1);
        DeliveryAttempt row = rows.get(0);
        assertThat(row.statusCode()).isEqualTo(401);
        assertThat(row.errorCode()).isEqualTo("HTTP_401");
        assertThat(row.outcome()).isEqualTo(DeliveryAttempt.FAILED);
        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/emails")))).hasSize(1);
        List<DeliveryAttempt> persisted = logRepository.listByRule(tenant, ruleId, 50, 0);
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).errorCode()).isEqualTo("HTTP_401");
    }

    private void seedRule(UUID tenant, UUID ruleId) {
        jdbc.update("INSERT INTO notification_rules (id, tenant_id) VALUES (:id, :t)",
                new MapSqlParameterSource("id", ruleId).addValue("t", tenant));
    }
}
