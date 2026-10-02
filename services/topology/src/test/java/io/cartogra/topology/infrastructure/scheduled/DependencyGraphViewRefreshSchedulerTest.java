package io.cartogra.topology.infrastructure.scheduled;

import io.cartogra.topology.repository.DependencyGraphViewRepository;
import io.cartogra.web.lock.AdvisoryLockRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DependencyGraphViewRefreshSchedulerTest {

    private final DependencyGraphViewRepository graphViewRepository = mock(DependencyGraphViewRepository.class);
    private final AdvisoryLockRepository lockRepository = mock(AdvisoryLockRepository.class);
    private final DependencyGraphViewRefreshScheduler scheduler =
            new DependencyGraphViewRefreshScheduler(graphViewRepository, lockRepository);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void markDirtyOutsideATransactionRefreshesOnTheNextTick() {
        when(lockRepository.tryAcquireLock(anyLong())).thenReturn(true);

        scheduler.markDirty();
        scheduler.tick();

        verify(graphViewRepository).refresh();
    }

    @Test
    void markDirtyInsideATransactionDoesNotRefreshBeforeCommit() {
        when(lockRepository.tryAcquireLock(anyLong())).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();

        scheduler.markDirty();
        scheduler.tick();

        verify(graphViewRepository, never()).refresh();
    }

    @Test
    void markDirtyInsideATransactionRefreshesAfterCommit() {
        when(lockRepository.tryAcquireLock(anyLong())).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();

        scheduler.markDirty();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        scheduler.tick();

        verify(graphViewRepository).refresh();
    }

    @Test
    void failedRefreshKeepsTheViewDirtyForTheNextTick() {
        when(lockRepository.tryAcquireLock(anyLong())).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).doNothing().when(graphViewRepository).refresh();

        scheduler.markDirty();
        scheduler.tick();
        scheduler.tick();

        org.mockito.Mockito.verify(graphViewRepository, org.mockito.Mockito.times(2)).refresh();
    }

    @Test
    void busyLockSkipsTheTickAndLeavesTheViewDirty() {
        when(lockRepository.tryAcquireLock(anyLong())).thenReturn(false, true);

        scheduler.markDirty();
        scheduler.tick();
        scheduler.tick();

        verify(graphViewRepository).refresh();
    }
}
