package io.cartogra.registry.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.registry.domain.Team;
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
class TeamLifecycleEventProducerTest {

    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void publishCreatedSendsToCorrectTopicWithEventType() {
        var producer = new TeamLifecycleEventProducer(kafkaTemplate);
        Team team = team(null);

        producer.publishCreated(team);

        ProducerRecord<String, Object> record = captureRecord();
        assertThat(record.topic()).isEqualTo("cartogra.registry.team.created");
        assertThat(record.key()).isEqualTo(team.id().toString());
        EventEnvelope<?> envelope = (EventEnvelope<?>) record.value();
        assertThat(envelope.eventType()).isEqualTo("team.created");
        assertThat(envelope.entityId()).isEqualTo(team.id());
        assertThat(envelope.tenantId()).isEqualTo(team.tenantId());
        assertThat(((Team) envelope.payload()).deletedAt()).isNull();
    }

    @Test
    void publishUpdatedSendsToCorrectTopicWithEventType() {
        var producer = new TeamLifecycleEventProducer(kafkaTemplate);

        producer.publishUpdated(team(null));

        ProducerRecord<String, Object> record = captureRecord();
        assertThat(record.topic()).isEqualTo("cartogra.registry.team.updated");
        EventEnvelope<?> envelope = (EventEnvelope<?>) record.value();
        assertThat(envelope.eventType()).isEqualTo("team.updated");
    }

    @Test
    void publishDeletedSetsDeletedAtOnPayload() {
        var producer = new TeamLifecycleEventProducer(kafkaTemplate);
        Instant deletedAt = Instant.now();
        Team deleted = team(deletedAt);

        producer.publishDeleted(deleted);

        ProducerRecord<String, Object> record = captureRecord();
        assertThat(record.topic()).isEqualTo("cartogra.registry.team.deleted");
        EventEnvelope<?> envelope = (EventEnvelope<?>) record.value();
        assertThat(envelope.eventType()).isEqualTo("team.deleted");
        assertThat(((Team) envelope.payload()).deletedAt()).isEqualTo(deletedAt);
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, Object> captureRecord() {
        var captor = ArgumentCaptor.forClass((Class<ProducerRecord<String, Object>>) (Class<?>) ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private Team team(Instant deletedAt) {
        Instant now = Instant.now();
        return new Team(UUID.randomUUID(), UUID.randomUUID(), "platform", now, now, deletedAt);
    }
}
