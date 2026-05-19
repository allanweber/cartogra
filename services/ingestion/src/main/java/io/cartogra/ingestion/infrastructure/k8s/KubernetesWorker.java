package io.cartogra.ingestion.infrastructure.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.api.model.Service;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.Arrays;
import java.util.List;

public class KubernetesWorker {

    private static final Logger log = LoggerFactory.getLogger(KubernetesWorker.class);

    private final KubernetesClient kubernetesClient;
    private final List<String> namespaces;
    private Closeable watch;

    public KubernetesWorker(KubernetesClient kubernetesClient, String namespacesConfig) {
        this.kubernetesClient = kubernetesClient;
        this.namespaces = Arrays.stream(namespacesConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    @PostConstruct
    public void start() {
        log.info("Starting Kubernetes worker watching namespaces: {}", namespaces);
        watch = kubernetesClient.services().inAnyNamespace().watch(new Watcher<>() {
            @Override
            public void eventReceived(Action action, Service service) {
                String namespace = service.getMetadata().getNamespace();
                if (!namespaces.contains(namespace) && !namespaces.contains("*")) {
                    return;
                }
                String name = service.getMetadata().getName();
                log.info("K8s service event action={} namespace={} name={}", action, namespace, name);
            }

            @Override
            public void onClose(WatcherException cause) {
                if (cause != null) {
                    log.warn("K8s watch closed with error: {}", cause.getMessage());
                }
            }
        });
    }

    @PreDestroy
    public void stop() {
        if (watch != null) {
            try {
                watch.close();
            } catch (Exception ex) {
                log.warn("Error closing K8s watch: {}", ex.getMessage());
            }
        }
    }
}
