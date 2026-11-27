package io.cartogra.contract.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** Every field is optional at the boundary; the service enforces each path's rules. */
public record NotificationRuleRequest(
        @Nullable UUID serviceId,
        @Nullable String slackWebhookUrl,
        @Nullable String teamsWebhookUrl,
        @Nullable List<@Email @Size(max = 255) String> emailAddresses,
        /** Create ignores this field; the patch path requires it. A new rule is enabled. */
        @Nullable Boolean enabled
) {
}
