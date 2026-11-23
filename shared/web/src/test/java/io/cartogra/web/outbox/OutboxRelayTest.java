package io.cartogra.web.outbox;

import io.cartogra.web.lock.AdvisoryLockRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import org.mockito.ArgumentMatchers;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    private static final UUID TENANT = UUID.randomUUID();

    private final FakeOutboxRepository repository = new FakeOutboxRepository();
    private final AdvisoryLockRepository lock = mock(AdvisoryLockRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final List<ProducerRecord<String, String>> sent = new ArrayList<>();
    private final OutboxRelay relay = new OutboxRelay(repository, kafka, lock, 100, Duration.ofSeconds(1));

    @Test
    void sendsRowsInIdOrderWithHeadersAndDeletesThem() {
        when(lock.tryAcquireLock(anyLong())).thenReturn(true);
        sendSucceeds();
        repository.add(message(1, "edge-a", Map.of("traceparent", "00-abc-def-01")));
        repository.add(message(2, "edge-b", Map.of()));

        int relayed = relay.relayBatch();

        assertThat(relayed).isEqualTo(2);
        assertThat(sent).extracting(ProducerRecord::key).containsExactly("edge-a", "edge-b");
        assertThat(new String(sent.getFirst().headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8))
                .isEqualTo("00-abc-def-01");
        assertThat(repository.rows).isEmpty();
        verify(lock).releaseLock(anyLong());
    }

    @Test
    void stopsAtFirstFailureAndKeepsTheFailedRowAndTheRest() {
        when(lock.tryAcquireLock(anyLong())).thenReturn(true);
        when(kafka.send(ArgumentMatchers.<ProducerRecord<String, String>>any())).thenAnswer(inv -> {
            ProducerRecord<String, String> record = inv.getArgument(0);
            if (record.key().equals("edge-b")) {
                return CompletableFuture.failedFuture(new IllegalStateException("broker down"));
            }
            sent.add(record);
            return CompletableFuture.completedFuture(mock(SendResult.class));
        });
        repository.add(message(1, "edge-a", Map.of()));
        repository.add(message(2, "edge-b", Map.of()));
        repository.add(message(3, "edge-c", Map.of()));

        int relayed = relay.relayBatch();

        assertThat(relayed).isEqualTo(1);
        assertThat(sent).extracting(ProducerRecord::key).containsExactly("edge-a");
        assertThat(repository.rows).extracting(OutboxMessage::id).containsExactly(2L, 3L);
        assertThat(repository.failures).containsKey(2L);
        assertThat(repository.failures.get(2L)).contains("broker down");
        verify(lock).releaseLock(anyLong());
    }

    @Test
    void skipsWhenAnotherReplicaHoldsTheLock() {
        when(lock.tryAcquireLock(anyLong())).thenReturn(false);
        repository.add(message(1, "edge-a", Map.of()));

        int relayed = relay.relayBatch();

        assertThat(relayed).isZero();
        assertThat(repository.rows).hasSize(1);
        verify(kafka, never()).send(ArgumentMatchers.<ProducerRecord<String, String>>any());
        verify(lock, never()).releaseLock(anyLong());
    }

    @Test
    void releasesTheLockWhenTheRepositoryFails() {
        when(lock.tryAcquireLock(anyLong())).thenReturn(true);
        repository.failOnRead = true;

        int relayed = relay.relayBatch();

        assertThat(relayed).isZero();
        verify(lock).releaseLock(anyLong());
    }

    private void sendSucceeds() {
        when(kafka.send(ArgumentMatchers.<ProducerRecord<String, String>>any())).thenAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return CompletableFuture.completedFuture(mock(SendResult.class));
        });
    }

    private static OutboxMessage message(long id, String key, Map<String, String> headers) {
        return new OutboxMessage(id, TENANT, "cartogra.test.thing.changed", key, "{\"id\":" + id + "}", headers);
    }

    private static final class FakeOutboxRepository implements OutboxRepository {

        final List<OutboxMessage> rows = new ArrayList<>();
        final Map<Long, String> failures = new java.util.HashMap<>();
        boolean failOnRead;

        void add(OutboxMessage message) {
            rows.add(message);
        }

        @Override
        public void insert(List<OutboxEntry> entries) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<OutboxMessage> findBatch(int limit) {
            if (failOnRead) {
                throw new IllegalStateException("db down");
            }
            return List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        }

        @Override
        public void delete(long id) {
            rows.removeIf(m -> m.id() == id);
        }

        @Override
        public void recordFailure(long id, String error) {
            failures.put(id, error);
        }
    }
}
