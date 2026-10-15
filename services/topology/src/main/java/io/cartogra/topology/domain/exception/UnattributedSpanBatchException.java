package io.cartogra.topology.domain.exception;

public class UnattributedSpanBatchException extends RuntimeException {

    public enum QuarantineReason {
        TENANT_MISSING,
        TENANT_MALFORMED,
        TENANT_UNKNOWN
    }

    private final QuarantineReason reason;

    public UnattributedSpanBatchException(QuarantineReason reason) {
        super("Span batch quarantined: " + reason);
        this.reason = reason;
    }

    public QuarantineReason reason() {
        return reason;
    }
}
