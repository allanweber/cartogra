package io.cartogra.contract.api.dto;

import io.cartogra.contract.domain.NotificationRule;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Webhooks and email addresses are masked here, before they leave the backend. */
public record NotificationRuleResponse(
        UUID id,
        @Nullable UUID serviceId,
        boolean enabled,
        @Nullable String slackWebhookUrl,
        @Nullable String teamsWebhookUrl,
        List<String> emailAddresses,
        Instant createdAt,
        Instant updatedAt
) {
    public static NotificationRuleResponse from(NotificationRule rule) {
        return new NotificationRuleResponse(
                rule.id(),
                rule.serviceId(),
                rule.enabled(),
                rule.slackWebhookUrl() == null ? null : NotificationRule.maskWebhook(rule.slackWebhookUrl()),
                rule.teamsWebhookUrl() == null ? null : NotificationRule.maskWebhook(rule.teamsWebhookUrl()),
                rule.emailAddresses().stream().map(NotificationRule::maskEmail).toList(),
                rule.createdAt(),
                rule.updatedAt());
    }
}
