package io.cartogra.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingestion.registry")
public record RegistryClientProperties(String baseUrl) {}
