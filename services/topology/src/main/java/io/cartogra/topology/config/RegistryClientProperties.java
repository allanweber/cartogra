package io.cartogra.topology.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "topology.registry")
public record RegistryClientProperties(String baseUrl, Duration timeout) {
}
