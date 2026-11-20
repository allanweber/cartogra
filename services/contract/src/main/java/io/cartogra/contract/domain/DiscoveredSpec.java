package io.cartogra.contract.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One content of an OpenAPI file that ingestion found at {@code filePath} in a repository. Contract stores a new row
 * only when the content differs from the last spec seen at that path.
 */
public record DiscoveredSpec(
        UUID id,
        UUID tenantId,
        @Nullable UUID connectionId,
        String repositoryFullName,
        String filePath,
        String contentSha256,
        String specRaw,
        DiscoveredSpecStatus status,
        @Nullable UUID serviceId,
        @Nullable UUID contractVersionId,
        List<String> errors,
        Instant createdAt,
        Instant updatedAt
) {
    public DiscoveredSpec {
        errors = List.copyOf(errors);
    }
}
