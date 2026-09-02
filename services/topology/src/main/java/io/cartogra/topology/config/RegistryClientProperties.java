package io.cartogra.topology.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "topology.registry")
public record RegistryClientProperties(String baseUrl) {
}
