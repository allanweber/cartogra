package io.cartogra.ingestion.application.port.out;

import java.time.Instant;

public record CommitInfo(Instant committedAt, String sha) {}
