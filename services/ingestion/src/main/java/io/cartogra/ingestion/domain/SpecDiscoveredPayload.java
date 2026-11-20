package io.cartogra.ingestion.domain;

import java.util.UUID;

/** The payload of {@code spec.discovered}. It has no service id: Contract resolves the repository to a service. */
public record SpecDiscoveredPayload(
        UUID tenantId,
        UUID connectionId,
        String repositoryFullName,
        String filePath,
        String contentSha256,
        String contentB64
) {}
