package io.cartogra.ingestion.infrastructure.k8s;

import io.cartogra.ingestion.domain.ServiceDiscoveredPayload;
import io.cartogra.ingestion.infrastructure.kafka.ServiceDiscoveredProducer;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class KubernetesWorker {

    private static final Logger log = LoggerFactory.getLogger(KubernetesWorker.class);
    private static final String TENANT_LABEL = "cartogra.io/tenant-id";

    private final KubernetesClient kubernetesClient;
    private final ServiceDiscoveredProducer serviceDiscoveredProducer;
    private Closeable serviceWatch;
    private Closeable endpointsWatch;
    private Closeable namespaceWatch;

    public KubernetesWorker(KubernetesClient kubernetesClient,
                             ServiceDiscoveredProducer serviceDiscoveredProducer) {
        this.kubernetesClient = kubernetesClient;
        this.serviceDiscoveredProducer = serviceDiscoveredProducer;
    }

    @PostConstruct
    public void start() {
        log.info("Starting Kubernetes worker watching all namespaces for label {}", TENANT_LABEL);

        serviceWatch = kubernetesClient.services().inAnyNamespace().watch(new Watcher<>() {
            @Override
            public void eventReceived(Action action, Service service) {
                if (action != Action.ADDED && action != Action.MODIFIED) return;
                String namespace = service.getMetadata().getNamespace();
                String name = service.getMetadata().getName();
                try {
                    handleServiceEvent(namespace, name);
                } catch (Exception ex) {
                    log.warn("Error handling K8s service event ns={} name={}: {}", namespace, name, ex.getMessage());
                }
            }

            @Override
            public void onClose(WatcherException cause) {
                if (cause != null) {
                    log.warn("K8s service watch closed with error: {}", cause.getMessage());
                }
            }
        });

        // Watch Namespaces so that adding the tenant label to an existing namespace
        // immediately triggers discovery for all its Services — no manual annotation needed.
        namespaceWatch = kubernetesClient.namespaces().watch(new Watcher<>() {
            @Override
            public void eventReceived(Action action, Namespace namespace) {
                if (action != Action.MODIFIED) return;
                Map<String, String> labels = namespace.getMetadata().getLabels();
                if (labels == null || !labels.containsKey(TENANT_LABEL)) return;
                String ns = namespace.getMetadata().getName();
                kubernetesClient.services().inNamespace(ns).list().getItems()
                        .forEach(svc -> {
                            String name = svc.getMetadata().getName();
                            try {
                                handleServiceEvent(ns, name);
                            } catch (Exception ex) {
                                log.warn("Error handling namespace label event ns={} svc={}: {}", ns, name, ex.getMessage());
                            }
                        });
            }

            @Override
            public void onClose(WatcherException cause) {
                if (cause != null) log.warn("K8s namespace watch closed with error: {}", cause.getMessage());
            }
        });

        // Watch Endpoints so pod readiness changes (scale up/down, rolling restarts)
        // are reflected without waiting for a Service MODIFIED event.
        endpointsWatch = kubernetesClient.endpoints().inAnyNamespace().watch(new Watcher<>() {
            @Override
            public void eventReceived(Action action, Endpoints endpoints) {
                if (action != Action.MODIFIED) return;
                String namespace = endpoints.getMetadata().getNamespace();
                String name = endpoints.getMetadata().getName();
                try {
                    handleServiceEvent(namespace, name);
                } catch (Exception ex) {
                    log.warn("Error handling K8s endpoints event ns={} name={}: {}", namespace, name, ex.getMessage());
                }
            }

            @Override
            public void onClose(WatcherException cause) {
                if (cause != null) {
                    log.warn("K8s endpoints watch closed with error: {}", cause.getMessage());
                }
            }
        });
    }

    @PreDestroy
    public void stop() {
        closeQuietly(serviceWatch, "service");
        closeQuietly(endpointsWatch, "endpoints");
        closeQuietly(namespaceWatch, "namespace");
    }

    public void handleServiceEvent(String namespace, String name) {
        Namespace ns = kubernetesClient.namespaces().withName(namespace).get();
        if (ns == null) return;

        Map<String, String> labels = ns.getMetadata().getLabels();
        if (labels == null || !labels.containsKey(TENANT_LABEL)) return;

        UUID tenantId;
        try {
            tenantId = UUID.fromString(labels.get(TENANT_LABEL));
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid tenant UUID in namespace label ns={}: {}", namespace, labels.get(TENANT_LABEL));
            return;
        }

        String healthStatus = computeHealthStatus(namespace, name);
        String externalId = namespace + "/" + name;

        var payload = new ServiceDiscoveredPayload(
                tenantId,
                null,
                "kubernetes",
                externalId,
                name,
                null,
                null,
                null,
                null,
                namespace,
                null,
                List.of(),
                healthStatus,
                null,
                null,
                null
        );

        serviceDiscoveredProducer.publish(payload);
        log.info("Published service.discovered for K8s service ns={} name={} tenant={}", namespace, name, tenantId);
    }

    private String computeHealthStatus(String namespace, String name) {
        try {
            Endpoints endpoints = kubernetesClient.endpoints().inNamespace(namespace).withName(name).get();
            if (endpoints == null) return "UNKNOWN";
            List<EndpointSubset> subsets = endpoints.getSubsets();
            if (subsets == null || subsets.isEmpty()) return "UNKNOWN";

            boolean anyReady = subsets.stream()
                    .anyMatch(s -> s.getAddresses() != null && !s.getAddresses().isEmpty());
            boolean anyNotReady = subsets.stream()
                    .anyMatch(s -> s.getNotReadyAddresses() != null && !s.getNotReadyAddresses().isEmpty());

            if (anyReady && anyNotReady) return "DEGRADED";
            if (anyReady) return "HEALTHY";
            if (anyNotReady) return "UNHEALTHY";
            return "UNKNOWN";
        } catch (Exception ex) {
            log.debug("Could not fetch Endpoints for ns={} name={}: {}", namespace, name, ex.getMessage());
            return "UNKNOWN";
        }
    }

    private void closeQuietly(Closeable c, String label) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ex) {
                log.warn("Error closing K8s {} watch: {}", label, ex.getMessage());
            }
        }
    }
}
