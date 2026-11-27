package io.cartogra.contract.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class BreakingChangeMessageTest {

    @Test
    void rendersServiceChangesConsumersAndLink() {
        UUID contractId = UUID.randomUUID();
        List<CompatibilityChange> changes = List.of(
                new CompatibilityChange(CompatibilityRule.PATH_REMOVED, CompatibilityChange.Direction.OPERATION,
                        null, "/orders", null, "Path /orders was removed"),
                new CompatibilityChange(CompatibilityRule.REQUEST_FIELD_REQUIRED_ADDED,
                        CompatibilityChange.Direction.REQUEST, "POST", "/orders", "requestBody.currency",
                        "Request field currency became required"));
        List<Consumer> consumers = IntStream.range(0, 12)
                .mapToObj(i -> new Consumer(UUID.randomUUID(), "consumer-" + i, List.of("declared"), List.of("http")))
                .toList();
        CheckResult result = new CheckResult(true, 7, changes, consumers);

        String text = BreakingChangeMessage.render("ledger-service", contractId, result).text();

        assertThat(text).contains("BREAKING contract change for ledger-service");
        assertThat(text).contains("Baseline version: 7");
        assertThat(text).contains("Path /orders was removed");
        assertThat(text).contains("Request field currency became required");
        assertThat(text).contains("path-removed", "request-field-required-added");
        assertThat(text).contains("Affected Consumers: 12");
        consumers.forEach(consumer -> assertThat(text).contains(consumer.serviceName()));
        assertThat(text).contains(BreakingChangeMessage.BASE_URL + "/contracts/" + contractId);
        assertThat(text).doesNotContain("openapi: 3.0.3");
    }
}
