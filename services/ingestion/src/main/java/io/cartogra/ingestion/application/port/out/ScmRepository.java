package io.cartogra.ingestion.application.port.out;

public record ScmRepository(
        String id,
        String name,
        String fullPath,
        String defaultBranch,
        boolean archived
) {}
