package io.cartogra.registry.application.repository;

public interface AdvisoryLockRepository {
    boolean tryAcquireLock(long key);
    void releaseLock(long key);
}
