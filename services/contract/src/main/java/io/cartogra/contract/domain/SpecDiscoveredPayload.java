package io.cartogra.contract.domain;

import java.util.UUID;

/** The payload of ingestion's {@code spec.discovered}; {@code contentB64} is the file content in base64. */
public record SpecDiscoveredPayload(
        UUID tenantId,
        UUID connectionId,
        String repositoryFullName,
        String filePath,
        String contentSha256,
        String contentB64
) {}
