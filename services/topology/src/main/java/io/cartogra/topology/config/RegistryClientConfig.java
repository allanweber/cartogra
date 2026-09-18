package io.cartogra.topology.config;

import io.cartogra.web.client.TraceparentRequestInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RegistryClientProperties.class)
public class RegistryClientConfig {

    @Bean
    public TraceparentRequestInterceptor traceparentRequestInterceptor() {
        return new TraceparentRequestInterceptor();
    }
}
