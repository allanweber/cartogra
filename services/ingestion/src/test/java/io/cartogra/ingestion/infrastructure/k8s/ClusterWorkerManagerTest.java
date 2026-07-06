package io.cartogra.ingestion.infrastructure.k8s;

import io.cartogra.ingestion.domain.Cluster;
import io.cartogra.ingestion.domain.ClusterSource;
import io.cartogra.ingestion.domain.ClusterStatus;
import io.cartogra.ingestion.infrastructure.kafka.ServiceDiscoveredProducer;
import io.cartogra.ingestion.repository.ClusterRepository;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@EnableKubernetesMockClient(crud = true, https = false)
class ClusterWorkerManagerTest {

    KubernetesMockServer mockServer;
    KubernetesClient client;

    private ClusterRepository repository;
    private CredentialEncryptor encryptor;
    private ServiceDiscoveredProducer producer;

    @BeforeEach
    void setUp() {
        repository = mock(ClusterRepository.class);
        encryptor = mock(CredentialEncryptor.class);
        producer = mock(ServiceDiscoveredProducer.class);
        when(repository.findAllActive()).thenReturn(List.of());
    }

    @Test
    void init_startsWorkersForAllActiveClusters() throws Exception {
        Cluster cluster = clusterPointingAt(mockServer.url("/"));
        when(repository.findAllActive()).thenReturn(List.of(cluster));

        ClusterWorkerManager manager = new ClusterWorkerManager(repository, encryptor, producer, 2000);
        manager.init();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(repository).updateStatus(eq(cluster.id()), eq(ClusterStatus.ACTIVE), isNull())
        );

        manager.stopAll();
    }

    @Test
    void start_updatesToActiveOnSuccessfulConnection() throws Exception {
        Cluster cluster = clusterPointingAt(mockServer.url("/"));

        ClusterWorkerManager manager = new ClusterWorkerManager(repository, encryptor, producer, 2000);
        manager.init();
        manager.start(cluster);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(repository).updateStatus(eq(cluster.id()), eq(ClusterStatus.ACTIVE), isNull())
        );

        manager.stopAll();
    }

    @Test
    void stop_closesWorkerForCluster() throws Exception {
        Cluster cluster = clusterPointingAt(mockServer.url("/"));

        ClusterWorkerManager manager = new ClusterWorkerManager(repository, encryptor, producer, 2000);
        manager.init();
        manager.start(cluster);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(repository).updateStatus(eq(cluster.id()), eq(ClusterStatus.ACTIVE), isNull())
        );

        manager.stop(cluster.id());
        manager.stopAll();
    }

    @Test
    void start_bootstrapsPreExistingServicesInLabeledNamespaces() throws Exception {
        String tenantId = UUID.randomUUID().toString();

        client.namespaces().resource(new NamespaceBuilder()
                .withNewMetadata().withName("my-ns").withLabels(Map.of("cartogra.io/tenant-id", tenantId)).endMetadata()
                .build()).create();
        client.services().inNamespace("my-ns").resource(new ServiceBuilder()
                .withNewMetadata().withName("my-svc").withNamespace("my-ns").endMetadata()
                .build()).create();

        Cluster cluster = clusterPointingAt(mockServer.url("/"));
        ClusterWorkerManager manager = new ClusterWorkerManager(repository, encryptor, producer, 2000);
        manager.init();
        manager.start(cluster);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(producer, atLeastOnce()).publish(argThat(p ->
                        "my-svc".equals(p.name()) && "my-ns".equals(p.k8sNamespace())))
        );

        manager.stopAll();
    }

    private static Cluster clusterPointingAt(String url) {
        Instant now = Instant.now();
        return new Cluster(UUID.randomUUID(), UUID.randomUUID(), "test", ClusterSource.MANUAL,
                url, null, null, true,
                ClusterStatus.CONNECTING, null, now, now, null, null);
    }
}
