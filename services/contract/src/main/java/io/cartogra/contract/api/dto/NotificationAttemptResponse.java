package io.cartogra.contract.api.dto;

import io.cartogra.contract.domain.DeliveryAttempt;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/** A masked view of one Delivery Attempt; never carries a raw destination. */
public record NotificationAttemptResponse(
        UUID id,
        UUID ruleId,
        @Nullable UUID checkId,
        int attempt,
        String channel,
        @Nullable String destinationHint,
        int waitMs,
        @Nullable Integer statusCode,
        @Nullable String errorCode,
        String outcome,
        Instant createdAt,
        @Nullable Instant finishedAt
) {
    public static NotificationAttemptResponse from(DeliveryAttempt log) {
        return new NotificationAttemptResponse(
                log.id(), log.ruleId(), log.checkId(), log.attempt(), log.channel(), log.destinationHint(),
                log.waitMs(), log.statusCode(), log.errorCode(), log.outcome(), log.createdAt(), log.finishedAt());
    }
}
