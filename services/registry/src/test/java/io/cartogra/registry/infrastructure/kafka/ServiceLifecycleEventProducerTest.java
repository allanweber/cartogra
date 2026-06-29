package io.cartogra.registry.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.ServiceHealthStatus;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ServiceLifecycleEventProducerTest {

    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void publishRegisteredSendsToCorrectTopicWithEventType() {
        var producer = new ServiceLifecycleEventProducer(kafkaTemplate);
        Service svc = service();

        producer.publishRegistered(svc);

        ProducerRecord<String, Object> record = captureRecord();
        assertThat(record.topic()).isEqualTo("cartogra.registry.service.registered");
        assertThat(record.key()).isEqualTo(svc.id().toString());
        EventEnvelope<?> envelope = (EventEnvelope<?>) record.value();
        assertThat(envelope.eventType()).isEqualTo("service.registered");
        assertThat(envelope.entityId()).isEqualTo(svc.id());
        assertThat(envelope.tenantId()).isEqualTo(svc.tenantId());
        assertThat(((Service) envelope.payload()).deletedAt()).isNull();
    }

    @Test
    void publishUpdatedSendsToCorrectTopicWithEventType() {
        var producer = new ServiceLifecycleEventProducer(kafkaTemplate);
        Service svc = service();

        producer.publishUpdated(svc);

        ProducerRecord<String, Object> record = captureRecord();
        assertThat(record.topic()).isEqualTo("cartogra.registry.service.updated");
        EventEnvelope<?> envelope = (EventEnvelope<?>) record.value();
        assertThat(envelope.eventType()).isEqualTo("service.updated");
    }

    @Test
    void publishDeletedSetsDeletedAtOnPayload() {
        var producer = new ServiceLifecycleEventProducer(kafkaTemplate);
        Service svc = service();
        Instant deletedAt = Instant.now();

        producer.publishDeleted(svc, deletedAt);

        ProducerRecord<String, Object> record = captureRecord();
        assertThat(record.topic()).isEqualTo("cartogra.registry.service.deleted");
        EventEnvelope<?> envelope = (EventEnvelope<?>) record.value();
        assertThat(envelope.eventType()).isEqualTo("service.deleted");
        assertThat(((Service) envelope.payload()).deletedAt()).isEqualTo(deletedAt);
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, Object> captureRecord() {
        var captor = ArgumentCaptor.forClass((Class<ProducerRecord<String, Object>>) (Class<?>) ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private Service service() {
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        return new Service(id, tenantId, "payments", null, null, null, null, null,
                ServiceHealthStatus.UNKNOWN, null, now, now, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }
}
