package io.cartogra.ingestion.domain;

/** An OpenAPI file that a sync found in a repository, at the probe path {@code filePath}. */
public record SpecFile(String filePath, String content) {}
