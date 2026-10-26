package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.BlastRadiusConnection;

public record BlastRadiusConnectionResponse(String dependencyType, String protocol) {
    public static BlastRadiusConnectionResponse from(BlastRadiusConnection connection) {
        return new BlastRadiusConnectionResponse(connection.type().name(), connection.protocol().name());
    }
}
