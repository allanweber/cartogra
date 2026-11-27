package io.cartogra.contract.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Controls whether webhook rules may target loopback destinations. */
@Validated
@ConfigurationProperties(prefix = "contract.notifications")
public record NotificationWebhookProperties(boolean allowLocalWebhooks) {
}
