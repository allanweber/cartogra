package io.cartogra.ingestion.domain;

import io.cartogra.ingestion.repository.AdvisoryLockRepository;
import io.cartogra.ingestion.infrastructure.kafka.SyncCommandProducer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledSyncServiceTest {

    private final AdvisoryLockRepository lockRepository = mock(AdvisoryLockRepository.class);
    private final SyncCommandProducer producer = mock(SyncCommandProducer.class);
    private final ScheduledSyncService service = new ScheduledSyncService(lockRepository, producer);

    private static ScmConnection connection() {
        Instant now = Instant.now();
        return new ScmConnection(UUID.randomUUID(), UUID.randomUUID(), "github", "{}",
                true, 15, now, null, null, null, false, now, now, null);
    }

    @Test
    void attemptForConnection_publishesWhenLockAcquired() {
        ScmConnection conn = connection();
        when(lockRepository.tryAcquireXactLock(conn.id().getMostSignificantBits())).thenReturn(true);

        service.attemptForConnection(conn);

        verify(producer).publish(conn);
    }

    @Test
    void attemptForConnection_skipsWhenLockHeldByAnotherInstance() {
        ScmConnection conn = connection();
        when(lockRepository.tryAcquireXactLock(conn.id().getMostSignificantBits())).thenReturn(false);

        service.attemptForConnection(conn);

        verify(producer, never()).publish(conn);
    }
}
