package io.cartogra.web.outbox;

import java.util.Map;
import java.util.UUID;

/** A row to insert into the outbox; {@link OutboxMessage} is the stored row with its send order. */
public record OutboxEntry(UUID tenantId, String topic, String key, String payload, Map<String, String> headers) {
}
