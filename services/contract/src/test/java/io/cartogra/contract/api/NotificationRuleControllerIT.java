package io.cartogra.contract.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cartogra.contract.AbstractContractIT;
import io.cartogra.contract.ContractHttp;
import io.cartogra.contract.domain.DeliveryAttempt;
import io.cartogra.contract.repository.DeliveryAttemptRepository;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.web.audit.KafkaAuditEventAdapter;
import org.awaitility.Awaitility;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class NotificationRuleControllerIT extends AbstractContractIT {

    private static final String RESEND_KEY = "test-resend-key";

    private static final WireMockServer WIRE_MOCK =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private DeliveryAttemptRepository logRepository;

    private ContractHttp http;
    private UUID tenant;

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
        registry.add("contract.resend.api-key", () -> RESEND_KEY);
    }

    @BeforeEach
    void setUp() {
        http = new ContractHttp(port);
        tenant = UUID.randomUUID();
    }

    @Test
    void crudValidationMaskingRolesAndAudit() throws Exception {
        UUID serviceId = UUID.randomUUID();
        String slack = "https://hooks.slack.com/services/T001/B001/SECRET";
        String teams = "https://example.com/webhooks/TEAMSECRET";
        String alice = "alice@example.com";
        String bob = "bob@example.com";
        String valid = ContractHttp.notificationRuleBody(serviceId, slack, teams, List.of(alice, bob), true);

        HttpResponse<String> created = http.createNotificationRule(tenant, "ADMIN", valid);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode rule = data(created);
        String id = rule.get("id").stringValue();
        assertThat(rule.get("serviceId").stringValue()).isEqualTo(serviceId.toString());
        assertThat(rule.get("enabled").booleanValue()).isTrue();
        assertThat(rule.get("slackWebhookUrl").stringValue()).isEqualTo("https://hooks.slack.com/services/***");
        assertThat(rule.get("teamsWebhookUrl").stringValue()).isEqualTo("https://example.com/webhooks/***");
        assertThat(maskedAddresses(rule.get("emailAddresses"))).containsExactly("a***@example.com", "b***@example.com");
        assertThat(created.body()).doesNotContain(slack, teams, alice, bob);
        assertThat(http.notificationRules(tenant, "/" + id).body()).doesNotContain(slack, teams, alice, bob);

        Map<String, Object> stored = jdbc.queryForMap("""
                SELECT slack_webhook_url, teams_webhook_url FROM notification_rules WHERE id = :id
                """, new MapSqlParameterSource("id", UUID.fromString(id)));
        assertThat(stored.get("slack_webhook_url")).isEqualTo(slack);
        assertThat(stored.get("teams_webhook_url")).isEqualTo(teams);
        String savedEmails = jdbc.queryForObject("SELECT email_addresses::text FROM notification_rules WHERE id = :id",
                new MapSqlParameterSource("id", UUID.fromString(id)), String.class);
        assertThat(maskedAddresses(objectMapper.readTree(savedEmails))).containsExactly(alice, bob);

        HttpResponse<String> conflict = http.createNotificationRule(tenant, "ADMIN", valid);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(errorCode(conflict)).isEqualTo("CONFLICT");

        List<String> twentyOneEmails = IntStream.range(0, 21)
                .mapToObj(index -> "user" + index + "@example.com").toList();
        for (String invalid : List.of(
                ContractHttp.notificationRuleBody(null, null, null, List.of(), true),
                ContractHttp.notificationRuleBody(null, "http://example.com/hook", null, List.of(), true),
                ContractHttp.notificationRuleBody(null, "https://registry:8081/hook", null, List.of(), true),
                ContractHttp.notificationRuleBody(null, "https://10.0.0.5/hook", null, List.of(), true),
                ContractHttp.notificationRuleBody(null, null, null, twentyOneEmails, true))) {
            HttpResponse<String> response = http.createNotificationRule(tenant, "ADMIN", invalid);
            assertThat(response.statusCode()).as(invalid).isEqualTo(400);
            assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(ruleCount()).isEqualTo(1);

        HttpResponse<String> forbidden = http.createNotificationRule(tenant, "VIEWER",
                ContractHttp.notificationRuleBody(UUID.randomUUID(), slack, null, List.of(alice), true));
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(errorCode(forbidden)).isEqualTo("FORBIDDEN");
        assertThat(ruleCount()).isEqualTo(1);

        String newSlack = "https://hooks.slack.com/services/T002/B002/NEWSECRET";
        String newEmail = "carol@example.com";
        HttpResponse<String> updated = http.putNotificationRule(tenant, "ADMIN", "/" + id,
                ContractHttp.notificationRuleBody(serviceId, newSlack, null, List.of(newEmail), true));
        assertThat(updated.statusCode()).isEqualTo(200);
        JsonNode changed = data(updated);
        assertThat(changed.get("slackWebhookUrl").stringValue()).isEqualTo("https://hooks.slack.com/services/***");
        assertThat(changed.get("teamsWebhookUrl").isNull()).isTrue();
        assertThat(maskedAddresses(changed.get("emailAddresses"))).containsExactly("c***@example.com");
        assertThat(updated.body()).doesNotContain(slack, teams, newSlack, alice, bob, newEmail);

        List<JsonNode> firstEvents = auditEvents(UUID.fromString(id), 2);
        assertThat(firstEvents).allSatisfy(event ->
                assertThat(event.get("payload").get("entityType").stringValue()).isEqualTo("notification_rule"));
        JsonNode updateEvent = firstEvents.stream()
                .filter(event -> "updated".equals(event.get("payload").get("action").stringValue()))
                .findFirst().orElseThrow();
        JsonNode updatePayload = updateEvent.get("payload").get("payload");
        assertThat(updatePayload.get("serviceId").stringValue()).isEqualTo(serviceId.toString());
        assertThat(updatePayload.get("enabled").booleanValue()).isTrue();
        assertThat(updatePayload.get("slackSet").booleanValue()).isTrue();
        assertThat(updatePayload.get("teamsSet").booleanValue()).isFalse();
        assertThat(updatePayload.get("emailCount").intValue()).isEqualTo(1);
        assertThat(updateEvent.toString()).doesNotContain(slack, teams, newSlack, alice, bob, newEmail);

        UUID secondId = UUID.randomUUID();
        String secondEmail = "dave@example.com";
        String secondBody = ContractHttp.notificationRuleBody(secondId, null, teams,
                List.of(secondEmail, "erin@example.com", "frank@example.com"), true);
        HttpResponse<String> secondCreated = http.createNotificationRule(tenant, "ADMIN", secondBody);
        assertThat(secondCreated.statusCode()).isEqualTo(201);
        String secondRuleId = data(secondCreated).get("id").stringValue();

        HttpResponse<String> disabled = http.postNotificationRule(tenant, "ADMIN", "/" + secondRuleId + "/disable", "");
        assertThat(disabled.statusCode()).isEqualTo(200);
        assertThat(data(disabled).get("enabled").booleanValue()).isFalse();

        List<JsonNode> secondEvents = auditEvents(UUID.fromString(secondRuleId), 2);
        JsonNode disableEvent = secondEvents.stream()
                .filter(event -> "disabled".equals(event.get("payload").get("action").stringValue()))
                .findFirst().orElseThrow();
        JsonNode disablePayload = disableEvent.get("payload").get("payload");
        assertThat(disablePayload.get("enabled").booleanValue()).isFalse();
        assertThat(disablePayload.get("serviceId").stringValue()).isEqualTo(secondId.toString());
        assertThat(disablePayload.get("slackSet").booleanValue()).isFalse();
        assertThat(disablePayload.get("teamsSet").booleanValue()).isTrue();
        assertThat(disablePayload.get("emailCount").intValue()).isEqualTo(3);
        assertThat(disableEvent.toString()).doesNotContain(teams, secondEmail, "erin@example.com", "frank@example.com");

        HttpResponse<String> deleted = http.deleteNotificationRule(tenant, "ADMIN", "/" + secondRuleId);
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(http.notificationRules(tenant, "/" + secondRuleId).statusCode()).isEqualTo(404);
    }

    @Test
    void createAlwaysEnablesNewRule() throws Exception {
        String body = ContractHttp.notificationRuleBody(UUID.randomUUID(), null, "https://example.com/hook",
                List.of("grace@example.com"), false);
        HttpResponse<String> created = http.createNotificationRule(tenant, "ADMIN", body);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID ruleId = UUID.fromString(data(created).get("id").stringValue());
        assertThat(data(created).get("enabled").booleanValue()).isTrue();

        Boolean stored = jdbc.queryForObject("SELECT enabled FROM notification_rules WHERE id = :id",
                new MapSqlParameterSource("id", ruleId), Boolean.class);
        assertThat(stored).isTrue();
    }

    @Test
    void enableRecordsNewEnabledStateInAudit() throws Exception {
        UUID serviceId = UUID.randomUUID();
        String createdBody = ContractHttp.notificationRuleBody(serviceId, null, "https://example.com/hook",
                List.of("flora@example.com"), true);
        HttpResponse<String> created = http.createNotificationRule(tenant, "ADMIN", createdBody);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID ruleId = UUID.fromString(data(created).get("id").stringValue());
        assertThat(data(created).get("enabled").booleanValue()).isTrue();

        HttpResponse<String> disabled = http.postNotificationRule(tenant, "ADMIN", "/" + ruleId + "/disable", "");
        assertThat(disabled.statusCode()).as(disabled.body()).isEqualTo(200);
        assertThat(data(disabled).get("enabled").booleanValue()).isFalse();

        HttpResponse<String> enabled = http.postNotificationRule(tenant, "ADMIN", "/" + ruleId + "/enable", "");
        assertThat(enabled.statusCode()).as(enabled.body()).isEqualTo(200);
        assertThat(data(enabled).get("enabled").booleanValue()).isTrue();

        List<JsonNode> events = auditEvents(ruleId, 3);
        JsonNode enableEvent = events.stream()
                .filter(event -> "enabled".equals(event.get("payload").get("action").stringValue()))
                .findFirst().orElseThrow();
        JsonNode enablePayload = enableEvent.get("payload").get("payload");
        assertThat(enablePayload.get("serviceId").stringValue()).isEqualTo(serviceId.toString());
        assertThat(enablePayload.get("enabled").booleanValue()).isTrue();
        assertThat(enableEvent.toString()).doesNotContain("example.com/hook", "flora@example.com");
    }

    @Test
    void testSendsToAllDestinationsAndRefusesDisabledRule() throws Exception {
        String slackUrl = "http://localhost:" + WIRE_MOCK.port() + "/slack";
        String teamsUrl = "http://localhost:" + WIRE_MOCK.port() + "/teams";
        String alice = "alice@example.com";
        String bob = "bob@example.com";
        WIRE_MOCK.stubFor(post(urlEqualTo("/slack")).willReturn(aResponse().withStatus(200)));
        WIRE_MOCK.stubFor(post(urlEqualTo("/teams")).willReturn(aResponse().withStatus(200)));
        WIRE_MOCK.stubFor(post(urlEqualTo("/emails")).willReturn(aResponse().withStatus(200)));

        String body = ContractHttp.notificationRuleBody(UUID.randomUUID(), slackUrl, teamsUrl,
                List.of(alice, bob), true);
        HttpResponse<String> created = http.createNotificationRule(tenant, "ADMIN", body);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String ruleId = data(created).get("id").stringValue();

        HttpResponse<String> tested = http.postNotificationRule(tenant, "ADMIN", "/" + ruleId + "/test", "");
        assertThat(tested.statusCode()).as(tested.body()).isEqualTo(200);
        JsonNode attempts = data(tested);
        assertThat(attempts.size()).isEqualTo(4);
        List<String> channels = new ArrayList<>();
        for (JsonNode attempt : attempts) {
            assertThat(attempt.get("checkId").isNull()).isTrue();
            assertThat(attempt.get("outcome").stringValue()).isEqualTo("sent");
            assertThat(attempt.get("statusCode").intValue()).isEqualTo(200);
            assertThat(attempt.get("attempt").intValue()).isEqualTo(1);
            channels.add(attempt.get("channel").stringValue());
        }
        assertThat(channels).containsExactlyInAnyOrder("slack", "teams", "email", "email");
        assertThat(tested.body()).doesNotContain(alice, bob);

        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/slack")))).hasSize(1);
        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/teams")))).hasSize(1);
        var emails = WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/emails")));
        assertThat(emails).hasSize(2);
        for (var request : WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/slack")))) {
            JsonNode message = objectMapper.readTree(request.getBodyAsString());
            assertThat(message.get("text").stringValue()).contains("[TEST]", "BREAKING contract change");
        }
        for (var request : WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/teams")))) {
            JsonNode message = objectMapper.readTree(request.getBodyAsString());
            assertThat(message.get("text").stringValue()).contains("[TEST]", "BREAKING contract change");
        }
        for (var request : emails) {
            JsonNode message = objectMapper.readTree(request.getBodyAsString());
            assertThat(message.get("text").stringValue()).contains("[TEST]", "BREAKING contract change");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + RESEND_KEY);
        }
        assertThat(emails).extracting(request -> objectMapper.readTree(request.getBodyAsString())
                        .get("to").get(0).stringValue())
                .containsExactlyInAnyOrder(alice, bob);
        assertThat(logRepository.listByRule(tenant, UUID.fromString(ruleId), 50, 0)).hasSize(4);

        HttpResponse<String> pausedCreated = http.createNotificationRule(tenant, "ADMIN",
                ContractHttp.notificationRuleBody(UUID.randomUUID(), slackUrl, teamsUrl, List.of(alice), true));
        assertThat(pausedCreated.statusCode()).isEqualTo(201);
        String disabledId = data(pausedCreated).get("id").stringValue();
        assertThat(data(pausedCreated).get("enabled").booleanValue()).isTrue();

        HttpResponse<String> paused = http.postNotificationRule(tenant, "ADMIN", "/" + disabledId + "/disable", "");
        assertThat(paused.statusCode()).as(paused.body()).isEqualTo(200);
        assertThat(data(paused).get("enabled").booleanValue()).isFalse();

        HttpResponse<String> refused = http.postNotificationRule(tenant, "ADMIN", "/" + disabledId + "/test", "");
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(errorCode(refused)).isEqualTo("VALIDATION_ERROR");
        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/slack")))).hasSize(1);
        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/teams")))).hasSize(1);
        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/emails")))).hasSize(2);
        assertThat(logRepository.listByRule(tenant, UUID.fromString(disabledId), 50, 0)).isEmpty();
    }

    @Test
    void testReturnsOnlyRowsWrittenByThatTest() throws Exception {
        String slackUrl = "http://localhost:" + WIRE_MOCK.port() + "/slack";
        String teamsUrl = "http://localhost:" + WIRE_MOCK.port() + "/teams";
        WIRE_MOCK.stubFor(post(urlEqualTo("/slack")).willReturn(aResponse().withStatus(200)));
        WIRE_MOCK.stubFor(post(urlEqualTo("/teams")).willReturn(aResponse().withStatus(200)));
        WIRE_MOCK.stubFor(post(urlEqualTo("/emails")).willReturn(aResponse().withStatus(200)));

        String body = ContractHttp.notificationRuleBody(UUID.randomUUID(), slackUrl, teamsUrl,
                List.of("alice@example.com", "bob@example.com"), true);
        HttpResponse<String> created = http.createNotificationRule(tenant, "ADMIN", body);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String ruleId = data(created).get("id").stringValue();

        HttpResponse<String> first = http.postNotificationRule(tenant, "ADMIN", "/" + ruleId + "/test", "");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        List<String> firstIds = attemptIds(first);

        UUID olderId = UUID.randomUUID();
        Instant older = Instant.now().minus(1, ChronoUnit.HOURS);
        logRepository.insertPending(new DeliveryAttempt(olderId, tenant, UUID.fromString(ruleId), null, 1,
                DeliveryAttempt.SLACK, "https://example.com/***", 0, 200, null, DeliveryAttempt.SENT,
                older, older));

        HttpResponse<String> second = http.postNotificationRule(tenant, "ADMIN", "/" + ruleId + "/test", "");
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        JsonNode attempts = data(second);
        assertThat(attempts.size()).isEqualTo(4);
        List<String> secondIds = attemptIds(second);
        assertThat(secondIds).doesNotContain(olderId.toString()).doesNotContainAnyElementsOf(firstIds);
        List<String> channels = new ArrayList<>();
        for (JsonNode attempt : attempts) {
            channels.add(attempt.get("channel").stringValue());
        }
        assertThat(channels).containsExactly("slack", "teams", "email", "email");
    }

    @Test
    void attemptsReturnsNewestFiftyInPageResult() {
        HttpResponse<String> created = http.createNotificationRule(tenant, "ADMIN",
                ContractHttp.notificationRuleBody(null, "https://hooks.slack.com/services/T001/B001/SECRET",
                        null, List.of(), true));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID ruleId = UUID.fromString(data(created).get("id").stringValue());

        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(80);
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < 80; index++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            logRepository.insertPending(new DeliveryAttempt(id, tenant, ruleId, null, 1,
                    DeliveryAttempt.EMAIL, "a***@example.com", 0, null, null,
                    DeliveryAttempt.PENDING, base.plusSeconds(index), null));
        }

        HttpResponse<String> response = http.notificationRules(tenant, "/" + ruleId + "/attempts");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode page = data(response);
        assertThat(page.get("total").longValue()).isEqualTo(80);
        assertThat(page.get("limit").intValue()).isEqualTo(50);
        assertThat(page.get("offset").intValue()).isZero();
        JsonNode items = page.get("items");
        assertThat(items.size()).isEqualTo(50);
        List<String> expected = IntStream.range(30, 80).mapToObj(index -> ids.get(index).toString()).toList();
        List<String> actual = new ArrayList<>();
        for (JsonNode item : items) {
            actual.add(item.get("id").stringValue());
            assertThat(item.get("ruleId").stringValue()).isEqualTo(ruleId.toString());
            assertThat(item.get("destinationHint").stringValue()).isEqualTo("a***@example.com");
            assertThat(response.body()).doesNotContain("SECRET", "alice@", "bob@");
        }
        assertThat(actual).containsExactlyElementsOf(expected.reversed());
    }

    @Test
    void patchAcceptsOnlyTheEnabledField() throws Exception {
        HttpResponse<String> created = http.createNotificationRule(tenant, "ADMIN",
                ContractHttp.notificationRuleBody(null, "https://hooks.slack.com/services/T001/B001/SECRET",
                        null, List.of("alice@example.com"), true));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String id = data(created).get("id").stringValue();

        HttpResponse<String> missing = http.patchNotificationRule(tenant, "ADMIN", "/" + id, "{}");
        assertThat(missing.statusCode()).as(missing.body()).isEqualTo(400);
        assertThat(errorCode(missing)).isEqualTo("VALIDATION_ERROR");

        HttpResponse<String> extra = http.patchNotificationRule(tenant, "ADMIN", "/" + id,
                "{\"enabled\":false,\"serviceId\":\"" + UUID.randomUUID() + "\"}");
        assertThat(extra.statusCode()).as(extra.body()).isEqualTo(400);
        assertThat(errorCode(extra)).isEqualTo("VALIDATION_ERROR");

        HttpResponse<String> patched = http.patchNotificationRule(tenant, "ADMIN", "/" + id, "{\"enabled\":false}");
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        assertThat(data(patched).get("enabled").booleanValue()).isFalse();
    }

    @Test
    void breakingPublishSendsNotificationWithoutACheckId() throws Exception {
        String webhook = "http://localhost:" + WIRE_MOCK.port() + "/hook";
        WIRE_MOCK.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(200)));
        HttpResponse<String> ruleResponse = http.createNotificationRule(tenant, "ADMIN",
                ContractHttp.notificationRuleBody(null, webhook, null, List.of(), true));
        assertThat(ruleResponse.statusCode()).as(ruleResponse.body()).isEqualTo(201);
        UUID ruleId = UUID.fromString(data(ruleResponse).get("id").stringValue());

        HttpResponse<String> created = http.create(tenant, UUID.randomUUID(), ContractCheckIT.BASELINE);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String contractId = data(created).get("id").stringValue();

        HttpResponse<String> published = http.publish(tenant, contractId, ContractCheckIT.BREAKING);
        assertThat(published.statusCode()).as(published.body()).isEqualTo(201);

        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            List<DeliveryAttempt> attempts = logRepository.listByRule(tenant, ruleId, 50, 0);
            assertThat(attempts).isNotEmpty();
            assertThat(attempts).allSatisfy(attempt -> assertThat(attempt.checkId()).isNull());
            assertThat(attempts).anySatisfy(attempt -> assertThat(attempt.outcome())
                    .isEqualTo(DeliveryAttempt.SENT));
        });
        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/hook")));
    }

    private List<String> attemptIds(HttpResponse<String> response) {
        List<String> values = new ArrayList<>();
        for (JsonNode attempt : data(response)) {
            values.add(attempt.get("id").stringValue());
        }
        return values;
    }

    private List<String> maskedAddresses(JsonNode addresses) {
        List<String> values = new ArrayList<>();
        for (JsonNode address : addresses) {
            values.add(address.stringValue());
        }
        return values;
    }

    private long ruleCount() {
        return jdbc.queryForObject("SELECT count(*) FROM notification_rules WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class);
    }

    private JsonNode data(HttpResponse<String> response) {
        return objectMapper.readTree(response.body()).get("data");
    }

    private String errorCode(HttpResponse<String> response) {
        return objectMapper.readTree(response.body()).get("error").get("code").stringValue();
    }

    private List<JsonNode> auditEvents(UUID ruleId, int expected) {
        List<JsonNode> events = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestSupport.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "notification-rule-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(KafkaAuditEventAdapter.TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline && events.size() < expected) {
                collect(consumer, ruleId, events, Duration.ofMillis(300));
            }
            if (events.size() < expected) {
                collect(consumer, ruleId, events, Duration.ofSeconds(1));
            }
        }
        assertThat(events).as("audit events for rule " + ruleId).hasSize(expected);
        return events;
    }

    private void collect(KafkaConsumer<String, String> consumer, UUID ruleId, List<JsonNode> events, Duration poll) {
        for (ConsumerRecord<String, String> record : consumer.poll(poll)) {
            if (ruleId.toString().equals(record.key())) {
                events.add(objectMapper.readTree(record.value()));
            }
        }
    }
}
