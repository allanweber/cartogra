package io.cartogra.contract.infrastructure.email;

import io.cartogra.contract.domain.DeliveryAttempt;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** Outbound port for one email destination. */
public interface EmailSender {

    /** The attempts written during this send, in try order, including every retry. */
    List<DeliveryAttempt> send(UUID tenantId, UUID ruleId, @Nullable UUID checkId, String emailAddress, String text);
}
