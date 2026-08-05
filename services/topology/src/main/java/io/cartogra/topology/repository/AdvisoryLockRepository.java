package io.cartogra.topology.repository;

public interface AdvisoryLockRepository {
    boolean tryAcquireLock(long key);
    void releaseLock(long key);

    static long lockKey(String namespace, Object scope) {
        return 0xFFFFFFFFL & (namespace + ":" + scope).hashCode();
    }
}
