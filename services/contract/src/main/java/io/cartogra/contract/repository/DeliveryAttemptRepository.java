package io.cartogra.contract.repository;

import io.cartogra.contract.domain.DeliveryAttempt;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DeliveryAttemptRepository {

    /** Makes an in-flight try visible before the provider call. */
    void insertPending(DeliveryAttempt log);

    void finish(UUID tenantId, UUID id, @Nullable Integer statusCode, @Nullable String errorCode, String outcome,
                Instant finishedAt);

    /** Attempts of one rule, newest first. */
    List<DeliveryAttempt> listByRule(UUID tenantId, UUID ruleId, int limit, int offset);

    long countByRule(UUID tenantId, UUID ruleId);
}
