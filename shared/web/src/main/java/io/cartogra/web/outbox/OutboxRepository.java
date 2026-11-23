package io.cartogra.web.outbox;

import java.util.List;

public interface OutboxRepository {

    /** Inserts the entries in one JDBC batch, in list order. */
    void insert(List<OutboxEntry> entries);

    /** The oldest rows first, in {@code id} order: the relay sends them in that order. */
    List<OutboxMessage> findBatch(int limit);

    void delete(long id);

    void recordFailure(long id, String error);
}
