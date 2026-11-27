package io.cartogra.contract.domain;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** The text sent to Slack, Teams, and email for one breaking check or one rule test. */
public record BreakingChangeMessage(String text) {

    public static final String BASE_URL = "https://cartogra.dev";

    public static BreakingChangeMessage render(String serviceName, @Nullable UUID contractId, CheckResult result) {
        StringBuilder text = new StringBuilder();
        appendHeader(text, serviceName, false);
        text.append("Baseline version: ").append(result.baselineVersion()).append('\n');
        appendChanges(text, result.changes());
        appendConsumers(text, result.affectedConsumers());
        appendLink(text, contractId);
        return new BreakingChangeMessage(text.toString());
    }

    public static BreakingChangeMessage test(String serviceName, @Nullable UUID contractId) {
        StringBuilder text = new StringBuilder();
        appendHeader(text, serviceName, true);
        text.append("Baseline version: n/a\n");
        text.append("Changes: none\n");
        appendConsumers(text, List.of());
        appendLink(text, contractId);
        return new BreakingChangeMessage(text.toString());
    }

    private static void appendHeader(StringBuilder text, String serviceName, boolean test) {
        if (test) {
            text.append("[TEST] ");
        }
        text.append("BREAKING contract change for ").append(serviceName).append('\n');
    }

    private static void appendChanges(StringBuilder text, List<CompatibilityChange> changes) {
        text.append("Changes: ").append(changes.size()).append('\n');
        changes.forEach(change -> {
            text.append("- ").append(change.rule().id()).append(": ").append(change.message());
            String location = change.method() == null ? change.path()
                    : change.method() + " " + change.path();
            text.append(" at ").append(location);
            if (change.location() != null) {
                text.append(' ').append(change.location());
            }
            text.append('\n');
        });
    }

    private static void appendConsumers(StringBuilder text, List<Consumer> consumers) {
        text.append("Affected Consumers: ").append(consumers.size()).append('\n');
        if (!consumers.isEmpty()) {
            text.append(consumers.stream()
                    .map(consumer -> consumer.serviceName() == null
                            ? consumer.serviceId().toString() : consumer.serviceName())
                    .collect(Collectors.joining(", "))).append('\n');
        }
    }

    private static void appendLink(StringBuilder text, @Nullable UUID contractId) {
        text.append("Contract: ").append(BASE_URL).append("/contracts");
        if (contractId != null) {
            text.append('/').append(contractId);
        }
        text.append('\n');
    }
}
