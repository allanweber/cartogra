package io.cartogra.common.audit;

public interface AuditEventPort {

    void record(AuditEvent event);
}
