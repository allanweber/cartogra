package io.cartogra.contract.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/** One try to send one destination of a Notification Rule. A test row has no check id. */
public record DeliveryAttempt(
        UUID id,
        UUID tenantId,
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
    public static final String PENDING = "pending";
    public static final String SENT = "sent";
    public static final String FAILED = "failed";

    public static final String SLACK = "slack";
    public static final String TEAMS = "teams";
    public static final String EMAIL = "email";

    public static final int FIRST_WAIT_MS = 0;
    public static final int SECOND_WAIT_MS = 1000;
    public static final int THIRD_WAIT_MS = 2000;
    public static final int MAX_ATTEMPTS = 3;

    public DeliveryAttempt markFinished(@Nullable Integer statusCode, @Nullable String errorCode, String outcome,
                                        Instant finishedAt) {
        return new DeliveryAttempt(id, tenantId, ruleId, checkId, attempt, channel, destinationHint, waitMs,
                statusCode, errorCode, outcome, createdAt, finishedAt);
    }
}
