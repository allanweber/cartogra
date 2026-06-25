package io.cartogra.ingestion.domain;

import org.jspecify.annotations.Nullable;

public record ScmRepository(
        String id,
        String name,
        String fullPath,
        String defaultBranch,
        boolean archived,
        @Nullable String description,
        @Nullable String repositoryUrl
) {}
