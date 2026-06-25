package io.cartogra.ingestion.domain;

import java.time.Instant;

public record CommitInfo(Instant committedAt, String sha) {}
