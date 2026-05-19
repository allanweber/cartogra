package io.cartogra.ingestion.infrastructure.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "ingestion.workers.k8s.enabled", havingValue = "true")
public class KubernetesWorkerConfig {

    @Bean
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }

    @Bean
    public KubernetesWorker kubernetesWorker(
            KubernetesClient kubernetesClient,
            @Value("${ingestion.workers.k8s.namespaces:default}") String namespaces) {
        return new KubernetesWorker(kubernetesClient, namespaces);
    }
}
