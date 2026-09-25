package io.cartogra.common.event;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventEnvelopeTest {

    private record SamplePayload(String name, int count) {}

    @Test
    void sameLogicalEventProducesTheSameEventId() {
        UUID entityId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        SamplePayload payload = new SamplePayload("svc", 3);

        EventEnvelope<SamplePayload> first = EventEnvelope.of("service.updated", entityId, tenantId, 1, payload);
        EventEnvelope<SamplePayload> second = EventEnvelope.of("service.updated", entityId, tenantId, 1, payload);

        assertThat(first.eventId()).isEqualTo(second.eventId());
    }

    @Test
    void differentPayloadContentProducesADifferentEventId() {
        UUID entityId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        EventEnvelope<SamplePayload> first =
                EventEnvelope.of("service.updated", entityId, tenantId, 1, new SamplePayload("svc", 3));
        EventEnvelope<SamplePayload> second =
                EventEnvelope.of("service.updated", entityId, tenantId, 1, new SamplePayload("svc", 4));

        assertThat(first.eventId()).isNotEqualTo(second.eventId());
    }

    @Test
    void differentEntityIdProducesADifferentEventIdForOtherwiseIdenticalPayload() {
        UUID tenantId = UUID.randomUUID();
        SamplePayload payload = new SamplePayload("svc", 3);

        EventEnvelope<SamplePayload> first =
                EventEnvelope.of("service.updated", UUID.randomUUID(), tenantId, 1, payload);
        EventEnvelope<SamplePayload> second =
                EventEnvelope.of("service.updated", UUID.randomUUID(), tenantId, 1, payload);

        assertThat(first.eventId()).isNotEqualTo(second.eventId());
    }

    @Test
    void correlationIdIsStillRandomPerCall() {
        UUID entityId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        SamplePayload payload = new SamplePayload("svc", 3);

        EventEnvelope<SamplePayload> first = EventEnvelope.of("service.updated", entityId, tenantId, 1, payload);
        EventEnvelope<SamplePayload> second = EventEnvelope.of("service.updated", entityId, tenantId, 1, payload);

        assertThat(first.correlationId()).isNotEqualTo(second.correlationId());
    }
}
