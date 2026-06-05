package io.cartogra.registry.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class HttpClientConfig {

    @Bean("healthProbeRestClient")
    public RestClient healthProbeRestClient(
            @Value("${registry.health.probe-timeout:PT5S}") Duration probeTimeout) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(probeTimeout)
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(probeTimeout);
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
