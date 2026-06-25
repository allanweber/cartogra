package io.cartogra.registry.repository;

public interface AdvisoryLockRepository {
    boolean tryAcquireLock(long key);
    void releaseLock(long key);
}
