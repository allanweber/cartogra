package io.cartogra.common.audit;

import java.util.List;

public interface AuditEventPort {

    void record(AuditEvent event);

    /** Records every event; a database-backed port overrides this with a single insert. */
    default void recordAll(List<AuditEvent> events) {
        events.forEach(this::record);
    }
}
