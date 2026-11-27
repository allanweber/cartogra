package io.cartogra.contract.infrastructure.slack;

import io.cartogra.contract.domain.DeliveryAttempt;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** Outbound port for a Slack or Teams webhook. */
public interface WebhookSender {

    /** The attempts written during this send, in try order, including every retry. */
    List<DeliveryAttempt> send(UUID tenantId, UUID ruleId, @Nullable UUID checkId, String channel,
                               String webhookUrl, String text);
}
