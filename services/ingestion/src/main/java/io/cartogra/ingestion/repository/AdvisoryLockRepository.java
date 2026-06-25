package io.cartogra.ingestion.repository;

public interface AdvisoryLockRepository {

    /**
     * Acquire a transaction-scoped PostgreSQL advisory lock. Returns immediately:
     * {@code true} if acquired, {@code false} if another transaction holds it.
     * The lock auto-releases at transaction end.
     */
    boolean tryAcquireXactLock(long key);
}
