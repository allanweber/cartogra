package io.cartogra.topology.domain;

public enum DependencyProtocol {
    HTTP,
    GRPC,
    KAFKA,
    DB;

    public String toDbValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
