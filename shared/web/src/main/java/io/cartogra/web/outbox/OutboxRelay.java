package io.cartogra.web.outbox;

import io.cartogra.web.lock.AdvisoryLockRepository;
import io.cartogra.web.tenant.TenantDb;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends outbox rows to Kafka in {@code id} order and deletes each row after the broker accepts it. One replica
 * relays at a time (advisory lock), and a failed send stops the batch: a later event for the same key must never
 * overtake an earlier one. Delivery is at least once, so consumers must be idempotent.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long LOCK_KEY = AdvisoryLockRepository.lockKey("outbox", "relay");

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AdvisoryLockRepository lockRepository;
    private final int batchSize;
    private final Duration sendTimeout;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate,
            AdvisoryLockRepository lockRepository, int batchSize, Duration sendTimeout) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.lockRepository = lockRepository;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
    }

    /** Returns the number of rows sent. */
    public int relayBatch() {
        return TenantDb.asSystem(this::relayUnderLock);
    }

    private int relayUnderLock() {
        if (!lockRepository.tryAcquireLock(LOCK_KEY)) {
            return 0;
        }
        try {
            int sent = 0;
            for (OutboxMessage message : repository.findBatch(batchSize)) {
                if (!send(message)) {
                    break;
                }
                repository.delete(message.id());
                sent++;
            }
            return sent;
        } catch (RuntimeException e) {
            log.error("Outbox relay failed", e);
            return 0;
        } finally {
            lockRepository.releaseLock(LOCK_KEY);
        }
    }

    private boolean send(OutboxMessage message) {
        var record = new ProducerRecord<>(message.topic(), message.key(), message.payload());
        message.headers().forEach((k, v) -> record.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
        try {
            kafkaTemplate.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            repository.recordFailure(message.id(), "interrupted");
            return false;
        } catch (ExecutionException | TimeoutException e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            log.warn("Outbox send failed id={} topic={} key={}: {}", message.id(), message.topic(), message.key(),
                    cause.toString());
            repository.recordFailure(message.id(), cause.toString());
            return false;
        }
    }
}
