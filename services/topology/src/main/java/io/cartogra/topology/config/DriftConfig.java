package io.cartogra.topology.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DriftProperties.class)
public class DriftConfig {
}
