package io.cartogra.web.outbox;

import java.util.Map;
import java.util.UUID;

public record OutboxMessage(long id, UUID tenantId, String topic, String key, String payload,
        Map<String, String> headers) {
}
