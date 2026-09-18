package io.cartogra.topology.domain.exception;

public class BackfillFailedException extends RuntimeException {
    private final int nodesUpserted;
    private final int failedAtOffset;

    public BackfillFailedException(int nodesUpserted, int failedAtOffset, Throwable cause) {
        super("Backfill failed after upserting " + nodesUpserted + " nodes, stopped at offset " + failedAtOffset, cause);
        this.nodesUpserted = nodesUpserted;
        this.failedAtOffset = failedAtOffset;
    }

    public int nodesUpserted() {
        return nodesUpserted;
    }

    public int failedAtOffset() {
        return failedAtOffset;
    }
}
