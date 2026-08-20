package io.cartogra.topology.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RegistryClientProperties.class)
public class RegistryClientConfig {
}
