package io.cartogra.topology.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SpanLinkProperties.class)
public class SpanLinkConfig {
}
