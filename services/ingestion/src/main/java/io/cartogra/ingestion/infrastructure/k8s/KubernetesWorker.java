package io.cartogra.ingestion.infrastructure.k8s;

import io.cartogra.ingestion.domain.ServiceDiscoveredPayload;
import io.cartogra.ingestion.infrastructure.kafka.ServiceDiscoveredProducer;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import org.jspecify.annotations.Nullable;
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
    private final String clusterName;
    private Closeable serviceWatch;
    private Closeable endpointsWatch;
    private Closeable namespaceWatch;

    public KubernetesWorker(KubernetesClient kubernetesClient,
                             ServiceDiscoveredProducer serviceDiscoveredProducer,
                             String clusterName) {
        this.kubernetesClient = kubernetesClient;
        this.serviceDiscoveredProducer = serviceDiscoveredProducer;
        this.clusterName = clusterName;
    }

    public void start() {
        log.info("Starting Kubernetes worker for cluster '{}' watching all namespaces for label {}",
                clusterName, TENANT_LABEL);

        serviceWatch = kubernetesClient.services().inAnyNamespace().watch(new Watcher<>() {
            @Override
            public void eventReceived(Action action, Service service) {
                if (action != Action.ADDED && action != Action.MODIFIED) return;
                String namespace = service.getMetadata().getNamespace();
                String name = service.getMetadata().getName();
                try {
                    handleServiceEvent(namespace, name, service, null);
                } catch (Exception ex) {
                    log.warn("Error handling K8s service event ns={} name={}: {}", namespace, name, ex.getMessage());
                }
            }

            @Override
            public void onClose(WatcherException cause) {
                if (cause != null) log.warn("K8s service watch closed with error: {}", cause.getMessage());
            }
        });

        // Watch Namespaces so that adding the tenant label to an existing namespace
        // immediately triggers discovery for all its Services.
        namespaceWatch = kubernetesClient.namespaces().watch(new Watcher<>() {
            @Override
            public void eventReceived(Action action, Namespace namespace) {
                if (action != Action.MODIFIED) return;
                Map<String, String> labels = namespace.getMetadata().getLabels();
                if (labels == null || !labels.containsKey(TENANT_LABEL)) return;
                String ns = namespace.getMetadata().getName();
                kubernetesClient.services().inNamespace(ns).list().getItems()
                        .forEach(svc -> {
                            try {
                                handleServiceEvent(ns, svc.getMetadata().getName(), svc, null);
                            } catch (Exception ex) {
                                log.warn("Error handling namespace label event ns={} svc={}: {}",
                                        ns, svc.getMetadata().getName(), ex.getMessage());
                            }
                        });
            }

            @Override
            public void onClose(WatcherException cause) {
                if (cause != null) log.warn("K8s namespace watch closed with error: {}", cause.getMessage());
            }
        });

        // Watch Endpoints: use the event object directly to avoid a race-condition re-fetch.
        endpointsWatch = kubernetesClient.endpoints().inAnyNamespace().watch(new Watcher<>() {
            @Override
            public void eventReceived(Action action, Endpoints endpoints) {
                if (action != Action.MODIFIED && action != Action.ADDED && action != Action.DELETED) return;
                String namespace = endpoints.getMetadata().getNamespace();
                String name = endpoints.getMetadata().getName();
                try {
                    handleServiceEvent(namespace, name, null, endpoints);
                } catch (Exception ex) {
                    log.warn("Error handling K8s endpoints event ns={} name={}: {}", namespace, name, ex.getMessage());
                }
            }

            @Override
            public void onClose(WatcherException cause) {
                if (cause != null) log.warn("K8s endpoints watch closed with error: {}", cause.getMessage());
            }
        });
    }

    public void stop() {
        closeQuietly(serviceWatch, "service");
        closeQuietly(endpointsWatch, "endpoints");
        closeQuietly(namespaceWatch, "namespace");
    }

    /** Periodic full resync — re-evaluates every service in every tenant namespace. */
    public void resync() {
        log.debug("K8s resync starting for cluster '{}'", clusterName);
        kubernetesClient.namespaces().list().getItems().stream()
                .filter(ns -> {
                    var labels = ns.getMetadata().getLabels();
                    return labels != null && labels.containsKey(TENANT_LABEL);
                })
                .forEach(ns -> {
                    String namespace = ns.getMetadata().getName();
                    kubernetesClient.services().inNamespace(namespace).list().getItems()
                            .forEach(svc -> {
                                try {
                                    handleServiceEvent(namespace, svc.getMetadata().getName(), svc, null);
                                } catch (Exception ex) {
                                    log.warn("Resync error ns={} svc={}: {}",
                                            namespace, svc.getMetadata().getName(), ex.getMessage());
                                }
                            });
                });
        log.debug("K8s resync complete for cluster '{}'", clusterName);
    }

    public void handleServiceEvent(String namespace, String name) {
        handleServiceEvent(namespace, name, null, null);
    }

    /**
     * Core event handler. Either svc or knownEndpoints (or both) may be pre-fetched by the
     * caller; any null argument is fetched on demand to avoid redundant API calls.
     */
    private void handleServiceEvent(String namespace, String name,
                                    @Nullable Service knownSvc,
                                    @Nullable Endpoints knownEndpoints) {
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

        Service svc = knownSvc != null
                ? knownSvc
                : kubernetesClient.services().inNamespace(namespace).withName(name).get();
        if (svc == null) return;

        String healthStatus = knownEndpoints != null
                ? computeHealthStatusFromEndpoints(knownEndpoints)
                : computeHealthStatus(namespace, name);
        String healthEndpoint = extractHealthEndpoint(svc, namespace);
        String deployment = extractDeploymentName(namespace, svc);

        var payload = new ServiceDiscoveredPayload(
                tenantId,
                null,
                "kubernetes",
                null,
                name,
                null,
                null,
                null,
                clusterName,
                namespace,
                deployment,
                List.of(),
                healthStatus,
                healthEndpoint,
                null,
                null
        );

        serviceDiscoveredProducer.publish(payload);
        log.info("Published service.discovered ns={} name={} cluster={} deployment={} health={} tenant={}",
                namespace, name, clusterName, deployment, healthStatus, tenantId);
    }

    private String computeHealthStatus(String namespace, String name) {
        try {
            Endpoints endpoints = kubernetesClient.endpoints().inNamespace(namespace).withName(name).get();
            return computeHealthStatusFromEndpoints(endpoints);
        } catch (Exception ex) {
            log.warn("Could not fetch Endpoints for ns={} name={}: {}", namespace, name, ex.getMessage());
            return "UNKNOWN";
        }
    }

    private String computeHealthStatusFromEndpoints(@Nullable Endpoints endpoints) {
        if (endpoints == null) return "UNKNOWN";
        List<EndpointSubset> subsets = endpoints.getSubsets();
        if (subsets == null || subsets.isEmpty()) return "UNHEALTHY";

        boolean anyReady = subsets.stream()
                .anyMatch(s -> s.getAddresses() != null && !s.getAddresses().isEmpty());
        boolean anyNotReady = subsets.stream()
                .anyMatch(s -> s.getNotReadyAddresses() != null && !s.getNotReadyAddresses().isEmpty());

        if (anyReady && anyNotReady) return "DEGRADED";
        if (anyReady) return "HEALTHY";
        if (anyNotReady) return "UNHEALTHY";
        return "UNKNOWN";
    }

    @Nullable
    private String extractHealthEndpoint(Service svc, String namespace) {
        try {
            String name = svc.getMetadata().getName();
            if (svc.getSpec() == null) return null;

            Map<String, String> selector = svc.getSpec().getSelector();
            if (selector == null || selector.isEmpty()) return null;

            List<Pod> pods = kubernetesClient.pods().inNamespace(namespace).withLabels(selector).list().getItems();
            if (pods.isEmpty()) return null;

            Pod pod = pods.get(0);
            if (pod.getSpec() == null || pod.getSpec().getContainers().isEmpty()) return null;

            Container container = pod.getSpec().getContainers().get(0);
            Probe probe = container.getReadinessProbe();
            if (probe == null) probe = container.getLivenessProbe();
            if (probe == null || probe.getHttpGet() == null) return null;

            String path = probe.getHttpGet().getPath();
            if (path == null || path.isBlank()) path = "/";

            int servicePort = resolveServicePort(svc, container, probe.getHttpGet().getPort());
            return "http://%s.%s.svc.cluster.local:%d%s".formatted(name, namespace, servicePort, path);
        } catch (Exception ex) {
            log.debug("Could not extract health endpoint for ns={} svc={}: {}",
                    namespace, svc.getMetadata().getName(), ex.getMessage());
            return null;
        }
    }

    @Nullable
    private String extractDeploymentName(String namespace, Service svc) {
        if (svc.getSpec() == null) return null;
        Map<String, String> svcSelector = svc.getSpec().getSelector();
        if (svcSelector == null || svcSelector.isEmpty()) return null;
        try {
            return kubernetesClient.apps().deployments().inNamespace(namespace).list().getItems().stream()
                    .filter(d -> {
                        if (d.getSpec() == null || d.getSpec().getSelector() == null) return false;
                        Map<String, String> matchLabels = d.getSpec().getSelector().getMatchLabels();
                        return matchLabels != null && svcSelector.entrySet().containsAll(matchLabels.entrySet());
                    })
                    .map(d -> d.getMetadata().getName())
                    .findFirst()
                    .orElse(null);
        } catch (Exception ex) {
            log.debug("Could not determine deployment for ns={} svc={}: {}",
                    namespace, svc.getMetadata().getName(), ex.getMessage());
            return null;
        }
    }

    private int resolveServicePort(Service svc, Container container, IntOrString probePort) {
        int containerPort;
        if (probePort.getIntVal() != null) {
            containerPort = probePort.getIntVal();
        } else {
            String portName = probePort.getStrVal();
            containerPort = container.getPorts() == null ? -1 : container.getPorts().stream()
                    .filter(p -> portName.equals(p.getName()))
                    .mapToInt(ContainerPort::getContainerPort)
                    .findFirst().orElse(-1);
            if (containerPort == -1) {
                List<ServicePort> svcPorts = svc.getSpec().getPorts();
                return svcPorts != null && !svcPorts.isEmpty() ? svcPorts.get(0).getPort() : 80;
            }
        }
        final int cp = containerPort;
        List<ServicePort> svcPorts = svc.getSpec().getPorts();
        if (svcPorts == null || svcPorts.isEmpty()) return cp;
        return svcPorts.stream()
                .filter(p -> p.getTargetPort() != null
                        && (Integer.valueOf(cp).equals(p.getTargetPort().getIntVal())
                            || String.valueOf(cp).equals(p.getTargetPort().getStrVal())))
                .mapToInt(ServicePort::getPort)
                .findFirst()
                .orElse(svcPorts.get(0).getPort());
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
