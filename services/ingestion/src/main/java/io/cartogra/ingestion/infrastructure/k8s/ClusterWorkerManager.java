package io.cartogra.ingestion.infrastructure.k8s;

import io.cartogra.ingestion.domain.Cluster;
import io.cartogra.ingestion.domain.ClusterStatus;
import io.cartogra.ingestion.infrastructure.kafka.ServiceDiscoveredProducer;
import io.cartogra.ingestion.repository.ClusterRepository;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ClusterWorkerManager {

    private static final Logger log = LoggerFactory.getLogger(ClusterWorkerManager.class);

    private final ClusterRepository clusterRepository;
    private final CredentialEncryptor encryptor;
    private final ServiceDiscoveredProducer producer;
    private final int connectionTimeoutMs;
    private final ConcurrentHashMap<UUID, KubernetesWorker> workers = new ConcurrentHashMap<>();

    public ClusterWorkerManager(ClusterRepository clusterRepository,
                                CredentialEncryptor encryptor,
                                ServiceDiscoveredProducer producer,
                                @Value("${ingestion.k8s.connection-timeout-ms:10000}") int connectionTimeoutMs) {
        this.clusterRepository = clusterRepository;
        this.encryptor = encryptor;
        this.producer = producer;
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    @PostConstruct
    public void init() {
        List<Cluster> clusters = clusterRepository.findAllActive();
        log.info("Reconnecting {} K8s cluster worker(s) on startup", clusters.size());
        clusters.forEach(cluster -> Thread.ofVirtual().start(() -> connectAndWatch(cluster)));
    }

    public void start(Cluster cluster) {
        Thread.ofVirtual().start(() -> connectAndWatch(cluster));
    }

    public void stop(UUID clusterId) {
        KubernetesWorker worker = workers.remove(clusterId);
        if (worker != null) {
            worker.stop();
            log.info("Stopped K8s worker for cluster {}", clusterId);
        }
    }

    @Scheduled(fixedDelayString = "${ingestion.k8s.resync-interval:PT5M}")
    public void resyncAll() {
        workers.forEach((clusterId, worker) ->
                Thread.ofVirtual().start(() -> {
                    try {
                        worker.resync();
                    } catch (Exception ex) {
                        log.warn("Periodic resync failed for cluster {}: {}", clusterId, ex.getMessage());
                    }
                }));
    }

    @PreDestroy
    public void stopAll() {
        workers.forEach((id, worker) -> {
            try {
                worker.stop();
            } catch (Exception ex) {
                log.warn("Error stopping K8s worker for cluster {}: {}", id, ex.getMessage());
            }
        });
        workers.clear();
    }

    private void connectAndWatch(Cluster cluster) {
        log.info("Connecting to K8s cluster '{}' ({})", cluster.name(), cluster.id());
        try {
            Config config;
            if (cluster.kubeconfigEncrypted() != null) {
                String rawKubeconfig = encryptor.decrypt(cluster.kubeconfigEncrypted());
                config = new ConfigBuilder(Config.fromKubeconfig(rawKubeconfig))
                        .withConnectionTimeout(connectionTimeoutMs)
                        .withRequestTimeout(connectionTimeoutMs)
                        .build();
            } else {
                String caCert = cluster.caCertPem() != null ? encryptor.decrypt(cluster.caCertPem()) : null;
                String token = cluster.saToken() != null ? encryptor.decrypt(cluster.saToken()) : null;
                config = new ConfigBuilder()
                        .withMasterUrl(cluster.apiServerUrl())
                        .withCaCertData(caCert)
                        .withOauthToken(token)
                        .withTrustCerts(cluster.skipTlsVerify())
                        .withConnectionTimeout(connectionTimeoutMs)
                        .withRequestTimeout(connectionTimeoutMs)
                        .build();
            }

            KubernetesClient client = new KubernetesClientBuilder().withConfig(config).build();
            client.getKubernetesVersion();

            KubernetesWorker worker = new KubernetesWorker(client, producer, cluster.name());
            workers.put(cluster.id(), worker);
            worker.start();

            clusterRepository.updateStatus(cluster.id(), ClusterStatus.ACTIVE, null);
            log.info("K8s worker active for cluster '{}' ({}), running initial resync", cluster.name(), cluster.id());
            worker.resync();
        } catch (Exception ex) {
            log.error("Failed to connect to K8s cluster '{}' ({}): {}", cluster.name(), cluster.id(), ex.getMessage());
            clusterRepository.updateStatus(cluster.id(), ClusterStatus.ERROR, ex.getMessage());
        }
    }
}
